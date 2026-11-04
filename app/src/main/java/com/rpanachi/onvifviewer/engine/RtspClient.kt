package com.rpanachi.onvifviewer.engine

import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal RTSP client for live cameras: OPTIONS / DESCRIBE / SETUP / PLAY on one TCP connection with
 * interleaved RTP (no UDP ports, no audio track). Supports Digest and Basic authentication, keeps the
 * session alive, and hands reassembled video access units to a listener. Runs on its own thread.
 */
class RtspClient(url: String, private val listener: Listener) {

    interface Listener {
        /** Called once the SDP is parsed. [parameterSets] are raw VPS/SPS/PPS NAL units from the SDP (may be empty). */
        fun onConnected(codec: VideoCodec, parameterSets: List<ByteArray>)
        fun onAccessUnit(au: AccessUnit)
        fun onDisconnected(reason: String)
    }

    enum class VideoCodec(val mime: String, val hevc: Boolean) {
        H264("video/avc", false),
        H265("video/hevc", true),
    }

    private class Response(val code: Int, val headers: List<Pair<String, String>>, val body: String) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
        fun headersAll(name: String): List<String> = headers.filter { it.first.equals(name, true) }.map { it.second }
    }

    private class Sdp(val codec: VideoCodec, val payloadType: Int, val control: String, val parameterSets: List<ByteArray>)

    private val host: String
    private val port: Int
    private val requestUrl: String
    private val user: String?
    private val pass: String

    @Volatile private var running = false
    private var socket: Socket? = null
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private var cseq = 1
    private var session: String? = null
    private var sessionTimeoutSec = 60
    private var authDigest: Map<String, String>? = null
    private var authBasic = false
    private var supportsGetParameter = false
    private var payloadType = -1
    private var keepaliveUrl = ""
    private var depacketizer: RtpDepacketizer? = null

    /** Bytes of interleaved RTP data received so far (for the bit-rate display). */
    @Volatile var bytesReceived = 0L
        private set

    init {
        val m = URL_PATTERN.matchEntire(url.trim()) ?: throw IllegalArgumentException("not an rtsp:// URL")
        val userInfo = m.groupValues[1]
        host = m.groupValues[2]
        port = m.groupValues[3].toIntOrNull() ?: 554
        requestUrl = "rtsp://$host:$port" + m.groupValues[4].ifEmpty { "/" }
        if (userInfo.isNotEmpty()) {
            val idx = userInfo.indexOf(':')
            user = percentDecode(if (idx >= 0) userInfo.substring(0, idx) else userInfo)
            pass = if (idx >= 0) percentDecode(userInfo.substring(idx + 1)) else ""
        } else {
            user = null
            pass = ""
        }
    }

    fun start() {
        running = true
        Thread({ run() }, "rtsp-$host").start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun run() {
        try {
            session()
        } catch (e: Exception) {
            if (running) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun session() {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        output = s.getOutputStream()

        val options = request("OPTIONS", requestUrl)
        supportsGetParameter = options.header("Public")?.contains("GET_PARAMETER") == true

        val describe = request("DESCRIBE", requestUrl, mapOf("Accept" to "application/sdp"))
        if (describe.code == 401) throw IOException("authentication failed (401)")
        if (describe.code != 200) throw IOException("DESCRIBE failed: ${describe.code}")
        val sdp = parseSdp(describe.body)
        payloadType = sdp.payloadType
        val base = describe.header("Content-Base") ?: describe.header("Content-Location") ?: requestUrl
        val trackUrl = when {
            sdp.control.startsWith("rtsp://", true) -> sdp.control
            sdp.control.isEmpty() || sdp.control == "*" -> base
            else -> (if (base.endsWith("/")) base else "$base/") + sdp.control
        }

        val setup = request("SETUP", trackUrl, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
        if (setup.code != 200) throw IOException("SETUP failed: ${setup.code}")
        val sessionHeader = setup.header("Session") ?: throw IOException("SETUP returned no session")
        session = sessionHeader.substringBefore(';').trim()
        Regex("timeout=(\\d+)").find(sessionHeader)?.let { sessionTimeoutSec = it.groupValues[1].toInt() }
        var rtpChannel = 0
        setup.header("Transport")?.let { t ->
            Regex("interleaved=(\\d+)").find(t)?.let { rtpChannel = it.groupValues[1].toInt() }
        }

        keepaliveUrl = base
        val play = request("PLAY", base, mapOf("Range" to "npt=0.000-"))
        if (play.code != 200) throw IOException("PLAY failed: ${play.code}")

        depacketizer = RtpDepacketizer(sdp.codec.hevc) { au -> listener.onAccessUnit(au) }
        listener.onConnected(sdp.codec, sdp.parameterSets)
        streamLoop(rtpChannel)
    }

    // ---- RTSP messages ----

    private fun request(method: String, uri: String, extra: Map<String, String> = emptyMap()): Response {
        send(method, uri, extra)
        var r = readResponse()
        if (r.code == 401 && user != null && setupAuth(r)) {
            send(method, uri, extra)
            r = readResponse()
        }
        return r
    }

    private fun send(method: String, uri: String, extra: Map<String, String>) {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq++).append("\r\n")
        sb.append("User-Agent: OnvifViewer\r\n")
        session?.let { sb.append("Session: ").append(it).append("\r\n") }
        authHeader(method, uri)?.let { sb.append("Authorization: ").append(it).append("\r\n") }
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun setupAuth(r: Response): Boolean {
        if (authDigest != null || authBasic) return false // already tried once: credentials are wrong
        val challenges = r.headersAll("WWW-Authenticate")
        val digest = challenges.firstOrNull { it.trim().startsWith("Digest", true) }
        if (digest != null) {
            val params = HashMap<String, String>()
            Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").findAll(digest.trim().removePrefix("Digest")).forEach { m ->
                params[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
            if (params.containsKey("realm") && params.containsKey("nonce")) {
                authDigest = params
                return true
            }
        }
        if (challenges.any { it.trim().startsWith("Basic", true) }) {
            authBasic = true
            return true
        }
        return false
    }

    private fun authHeader(method: String, uri: String): String? {
        val u = user ?: return null
        authDigest?.let { d ->
            val realm = d["realm"] ?: ""
            val nonce = d["nonce"] ?: ""
            val ha1 = md5("$u:$realm:$pass")
            val ha2 = md5("$method:$uri")
            val qop = d["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            return if (qop != null) {
                val nc = "00000001"
                val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val response = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
                "Digest username=\"$u\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", qop=$qop, nc=$nc, cnonce=\"$cnonce\", response=\"$response\""
            } else {
                val response = md5("$ha1:$nonce:$ha2")
                "Digest username=\"$u\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
            }
        }
        if (authBasic) return "Basic " + Base64.encodeToString("$u:$pass".toByteArray(), Base64.NO_WRAP)
        return null
    }

    private fun readResponse(): Response {
        var statusLine = readLine()
        while (statusLine.isEmpty()) statusLine = readLine()
        if (!statusLine.startsWith("RTSP/")) throw IOException("bad RTSP response: ${statusLine.take(40)}")
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
        val headers = readHeaders()
        val len = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val b = ByteArray(len)
            readFully(b, 0, len)
            String(b, Charsets.ISO_8859_1)
        } else {
            ""
        }
        return Response(code, headers, body)
    }

    private fun readHeaders(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) list += line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        return list
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readFully(buf: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = input.read(buf, off + n, len - n)
            if (r < 0) throw IOException("connection closed")
            n += r
        }
    }

    // ---- SDP ----

    private fun parseSdp(sdp: String): Sdp {
        val lines = sdp.lines().map { it.trim() }
        var i = lines.indexOfFirst { it.startsWith("m=video") }
        if (i < 0) throw IOException("no video track in SDP")
        val pts = lines[i].split(' ').drop(3).mapNotNull { it.toIntOrNull() }
        val rtpmap = HashMap<Int, String>()
        val fmtps = HashMap<Int, String>()
        var control = ""
        i++
        while (i < lines.size && !lines[i].startsWith("m=")) {
            val l = lines[i]
            when {
                l.startsWith("a=rtpmap:") -> {
                    val rest = l.removePrefix("a=rtpmap:")
                    val pt = rest.substringBefore(' ').toIntOrNull()
                    if (pt != null) rtpmap[pt] = rest.substringAfter(' ', "").substringBefore('/').trim().uppercase()
                }
                l.startsWith("a=fmtp:") -> {
                    val rest = l.removePrefix("a=fmtp:")
                    val pt = rest.substringBefore(' ').toIntOrNull()
                    if (pt != null) fmtps[pt] = rest.substringAfter(' ', "")
                }
                l.startsWith("a=control:") -> control = l.removePrefix("a=control:").trim()
            }
            i++
        }
        val choice = pts.firstNotNullOfOrNull { pt ->
            when (rtpmap[pt]) {
                "H264" -> pt to VideoCodec.H264
                "H265", "HEVC" -> pt to VideoCodec.H265
                else -> null
            }
        } ?: throw IOException("unsupported video codec: ${pts.map { rtpmap[it] ?: "pt$it" }}")
        val params = fmtps[choice.first] ?: ""
        val sets = ArrayList<ByteArray>()
        fun add(b64: String) {
            runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { sets += it }
        }
        if (choice.second.hevc) {
            for (key in listOf("sprop-vps", "sprop-sps", "sprop-pps")) fmtpParam(params, key)?.split(',')?.forEach { add(it) }
        } else {
            fmtpParam(params, "sprop-parameter-sets")?.split(',')?.forEach { add(it) }
        }
        return Sdp(choice.second, choice.first, control, sets)
    }

    private fun fmtpParam(params: String, key: String): String? =
        params.split(';').map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    // ---- interleaved data ----

    private fun streamLoop(rtpChannel: Int) {
        val hdr = ByteArray(3)
        var packet = ByteArray(65536)
        var lastKeepalive = System.currentTimeMillis()
        val keepaliveMs = (sessionTimeoutSec * 1000L / 2).coerceIn(5_000L, 30_000L)
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastKeepalive > keepaliveMs) {
                lastKeepalive = now
                send(if (supportsGetParameter) "GET_PARAMETER" else "OPTIONS", keepaliveUrl, emptyMap())
            }
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            if (b == '$'.code) {
                readFully(hdr, 0, 3)
                val ch = hdr[0].toInt() and 0xFF
                val len = ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                if (len > packet.size) packet = ByteArray(len)
                readFully(packet, 0, len)
                bytesReceived += len + 4
                if (ch == rtpChannel) handleRtp(packet, len)
            } else {
                // An RTSP message in the data stream: a keep-alive reply, or a request from the camera.
                val first = b.toChar() + readLine()
                val headers = readHeaders()
                val len = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull() ?: 0
                if (len > 0) readFully(ByteArray(len), 0, len)
                if (!first.startsWith("RTSP/")) {
                    val cs = headers.firstOrNull { it.first.equals("CSeq", true) }?.second ?: "0"
                    output.write("RTSP/1.0 200 OK\r\nCSeq: $cs\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
            }
        }
    }

    private fun handleRtp(p: ByteArray, len: Int) {
        if (len < 12) return
        val b0 = p[0].toInt() and 0xFF
        if ((b0 shr 6) != 2) return
        val padding = (b0 and 0x20) != 0
        val extension = (b0 and 0x10) != 0
        val cc = b0 and 0x0F
        val b1 = p[1].toInt() and 0xFF
        val marker = (b1 and 0x80) != 0
        if ((b1 and 0x7F) != payloadType) return
        val ts = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or
            ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
        var off = 12 + cc * 4
        if (extension) {
            if (off + 4 > len) return
            val extLen = (((p[off + 2].toInt() and 0xFF) shl 8) or (p[off + 3].toInt() and 0xFF)) * 4
            off += 4 + extLen
        }
        var end = len
        if (padding) end -= (p[len - 1].toInt() and 0xFF)
        if (off >= end) return
        depacketizer?.onRtpPayload(p, off, end - off, ts, marker)
    }

    private companion object {
        val URL_PATTERN = Regex("^rtsp://(?:([^@/]*)@)?([^/:?]+)(?::(\\d+))?(/.*)?$", RegexOption.IGNORE_CASE)
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000

        fun percentDecode(s: String): String =
            if (s.contains('%')) runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s) else s

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }
}
