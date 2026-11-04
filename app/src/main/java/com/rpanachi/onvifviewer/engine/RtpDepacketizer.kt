package com.rpanachi.onvifviewer.engine

/** One compressed picture (access unit) in Annex-B form. [data] is owned by the receiver. */
class AccessUnit(
    val data: ByteArray,
    val size: Int,
    val timestampUs: Long,
    val keyframe: Boolean,
    val hasParameterSets: Boolean,
)

fun interface AuSink {
    fun onAccessUnit(au: AccessUnit)
}

/**
 * Reassembles RTP payloads into Annex-B access units for H.264 (RFC 6184: single NAL, STAP-A, FU-A)
 * or H.265 (RFC 7798: single NAL, AP, FU). An access unit ends at the RTP marker bit or when the
 * RTP timestamp changes.
 */
class RtpDepacketizer(private val hevc: Boolean, private val sink: AuSink) {

    private var buf = ByteArray(256 * 1024)
    private var len = 0
    private var fuStarted = false
    private var keyframe = false
    private var paramSets = false
    private var auTs = -1L
    private var firstRtpTs = -1L
    private var lastRtpTs = -1L
    private var wrapBase = 0L

    fun onRtpPayload(p: ByteArray, off: Int, n: Int, rtpTimestamp: Long, marker: Boolean) {
        if (n < (if (hevc) 3 else 2)) return
        if (auTs != -1L && rtpTimestamp != auTs && len > 0) flush(auTs)
        auTs = rtpTimestamp
        if (hevc) handleHevc(p, off, n) else handleH264(p, off, n)
        if (marker) flush(rtpTimestamp)
    }

    private fun flush(rtpTs: Long) {
        if (len > 0) {
            sink.onAccessUnit(AccessUnit(buf.copyOf(len), len, toUs(rtpTs), keyframe, paramSets))
        }
        len = 0
        keyframe = false
        paramSets = false
        fuStarted = false
        auTs = -1L
    }

    private fun toUs(rtpTs: Long): Long {
        if (firstRtpTs < 0) firstRtpTs = rtpTs
        if (lastRtpTs >= 0 && rtpTs < lastRtpTs && lastRtpTs - rtpTs > 0x80000000L) wrapBase += 0x100000000L
        lastRtpTs = rtpTs
        val rel = rtpTs + wrapBase - firstRtpTs
        return rel * 1_000_000L / 90_000L
    }

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }

    private fun startCode() {
        ensure(4)
        buf[len++] = 0
        buf[len++] = 0
        buf[len++] = 0
        buf[len++] = 1
    }

    private fun append(p: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        ensure(n)
        System.arraycopy(p, off, buf, len, n)
        len += n
    }

    private fun noteNal(type: Int) {
        if (hevc) {
            if (type in 16..21) keyframe = true
            if (type in 32..34) paramSets = true
        } else {
            if (type == 5) keyframe = true
            if (type == 7 || type == 8) paramSets = true
        }
    }

    private fun handleH264(p: ByteArray, off: Int, n: Int) {
        val type = p[off].toInt() and 0x1F
        when {
            type in 1..23 -> {
                startCode()
                noteNal(type)
                append(p, off, n)
            }
            type == 24 -> { // STAP-A
                var i = off + 1
                val end = off + n
                while (i + 2 <= end) {
                    val sz = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (sz <= 0 || i + sz > end) break
                    startCode()
                    noteNal(p[i].toInt() and 0x1F)
                    append(p, i, sz)
                    i += sz
                }
            }
            type == 28 -> { // FU-A
                val fu = p[off + 1].toInt() and 0xFF
                val nalType = fu and 0x1F
                if ((fu and 0x80) != 0) {
                    startCode()
                    noteNal(nalType)
                    ensure(1)
                    buf[len++] = ((p[off].toInt() and 0xE0) or nalType).toByte()
                    fuStarted = true
                }
                if (fuStarted) append(p, off + 2, n - 2)
                if ((fu and 0x40) != 0) fuStarted = false
            }
            else -> Unit // STAP-B / MTAP / FU-B are not used by cameras
        }
    }

    private fun handleHevc(p: ByteArray, off: Int, n: Int) {
        val type = (p[off].toInt() shr 1) and 0x3F
        when {
            type < 48 -> {
                startCode()
                noteNal(type)
                append(p, off, n)
            }
            type == 48 -> { // aggregation packet
                var i = off + 2
                val end = off + n
                while (i + 2 <= end) {
                    val sz = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (sz <= 0 || i + sz > end) break
                    startCode()
                    noteNal((p[i].toInt() shr 1) and 0x3F)
                    append(p, i, sz)
                    i += sz
                }
            }
            type == 49 -> { // fragmentation unit
                val fu = p[off + 2].toInt() and 0xFF
                val nalType = fu and 0x3F
                if ((fu and 0x80) != 0) {
                    startCode()
                    noteNal(nalType)
                    ensure(2)
                    buf[len++] = ((p[off].toInt() and 0x81) or (nalType shl 1)).toByte()
                    buf[len++] = p[off + 1]
                    fuStarted = true
                }
                if (fuStarted) append(p, off + 3, n - 3)
                if ((fu and 0x40) != 0) fuStarted = false
            }
            else -> Unit
        }
    }
}
