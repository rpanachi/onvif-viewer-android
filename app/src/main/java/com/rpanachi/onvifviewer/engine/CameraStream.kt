package com.rpanachi.onvifviewer.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.rpanachi.onvifviewer.model.Camera
import com.rpanachi.onvifviewer.model.StreamUrls
import com.rpanachi.onvifviewer.ui.CameraTileView
import com.rpanachi.onvifviewer.ui.GlCompositorView

/**
 * One camera on screen: RTSP client -> VideoDecoder -> compositor tile [slot]. Reconnects with exponential
 * back-off on any failure and restarts when no frames arrive. Public methods run on the main thread.
 */
class CameraStream(
    val camera: Camera,
    val url: String,
    val slot: Int,
    private val compositor: GlCompositorView,
    val tile: CameraTileView,
    renderIntervalMs: Long,
) : RtspClient.Listener, VideoDecoder.Listener {

    /** Minimum stream time between displayed frames; 0 shows every frame. Can change while running. */
    @Volatile var renderIntervalMs: Long = renderIntervalMs
        set(value) {
            field = value
            decoder?.renderIntervalUs = value * 1000
        }

    /** Called on the main thread each time a connection shows its first frame. */
    var onLive: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var client: RtspClient? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var codec: RtspClient.VideoCodec? = null
    @Volatile private var pixelAspect = 1f
    private var retryDelayMs = INITIAL_RETRY_MS
    @Volatile private var live = false
    private var lastDecodedCount = -1L
    private var lastProgressAt = 0L

    var state = "idle"
        private set
    /** e.g. "H264 640x480", known once the SPS was parsed. */
    @Volatile var videoInfo = ""
        private set
    /** elapsedRealtime when the first frame of the current connection was shown, 0 while not live. */
    @Volatile var liveSince = 0L
        private set
    var reconnects = 0
        private set
    private var bytesFromPreviousClients = 0L
    val bytesReceived: Long get() = bytesFromPreviousClients + (client?.bytesReceived ?: 0L)
    val decoderName: String? get() = decoder?.codecName
    val keyframesOnly: Boolean get() = decoder?.keyframesOnly == true
    val framesDecoded: Long get() = decoder?.framesDecoded ?: 0L
    val framesRendered: Long get() = decoder?.framesRendered ?: 0L
    val framesDropped: Long get() = decoder?.framesDropped ?: 0L

    fun start() = connect()

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        dropClient()
        releaseDecoder()
        setState("stopped")
    }

    private fun dropClient() {
        client?.let {
            it.stop()
            bytesFromPreviousClients += it.bytesReceived
        }
        client = null
        liveSince = 0L
    }

    private fun connect() {
        if (stopped) return
        live = false
        lastDecodedCount = -1
        lastProgressAt = SystemClock.elapsedRealtime()
        val c = try {
            RtspClient(url, this)
        } catch (e: IllegalArgumentException) {
            setState("bad URL")
            return
        }
        client = c
        setState("connecting")
        Log.i(TAG, "${camera.name}: connecting to ${StreamUrls.mask(url)}")
        c.start()
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun releaseDecoder() {
        val d = decoder ?: return
        decoder = null
        Engine.releaseExecutor.execute { d.release() }
    }

    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        dropClient()
        releaseDecoder()
        reconnects++
        main.removeCallbacks(watchdog)
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        Log.w(TAG, "${camera.name}: $reason; reconnecting in ${delay}ms")
        main.postDelayed({ connect() }, delay)
    }

    private fun setState(s: String) {
        state = s
        tile.setStatus(camera.name, if (s == "live") null else s)
    }

    // ---- RtspClient.Listener (RTSP thread) ----

    override fun onConnected(codec: RtspClient.VideoCodec, parameterSets: List<ByteArray>) {
        this.codec = codec
        main.post { if (!stopped) setState("waiting for video") }
        if (parameterSets.isNotEmpty()) configureDecoder(parameterSets)
    }

    override fun onAccessUnit(au: AccessUnit) {
        if (stopped) return
        if (decoder == null && au.hasParameterSets) {
            val hevc = codec?.hevc ?: return
            configureDecoder(AnnexB.nalUnits(au.data, au.size).filter { AnnexB.isParameterSet(it, hevc) })
        }
        decoder?.submit(au)
    }

    override fun onDisconnected(reason: String) {
        main.post {
            if (!stopped) {
                setState("error: ${shortReason(reason)}")
                scheduleReconnect(reason)
            }
        }
    }

    private fun shortReason(reason: String): String = when {
        reason.contains("failed to connect", true) -> "unreachable"
        reason.contains("timed out", true) || reason.contains("timeout", true) -> "timeout"
        reason.contains("Connection reset", true) || reason.contains("connection closed", true) -> "connection closed"
        reason.contains("401") -> "wrong user or password"
        reason.contains("unsupported video codec", true) -> reason
        else -> reason.take(40)
    }

    private fun configureDecoder(parameterSets: List<ByteArray>) {
        if (decoder != null || stopped) return
        val c = codec ?: return
        val sps = parameterSets.firstOrNull { AnnexB.nalType(it, c.hevc) == (if (c.hevc) 33 else 7) } ?: return
        val dims = runCatching { if (c.hevc) SpsParser.parseH265(sps) else SpsParser.parseH264(sps) }
            .getOrElse {
                Log.w(TAG, "${camera.name}: SPS parse failed (${it.message}), assuming 1280x720")
                VideoDims(1280, 720)
            }
        pixelAspect = dims.pixelAspect
        videoInfo = "${c.name} ${dims.width}x${dims.height}"
        val surface = waitForSurface() ?: run {
            main.post { if (!stopped) setState("display not ready") }
            return
        }
        val d = VideoDecoder(c.mime, dims, parameterSets, surface, Engine.codecHandler, this)
        d.renderIntervalUs = renderIntervalMs * 1000
        if (!d.start()) {
            main.post { if (!stopped) setState("no ${c.name} decoder") }
            return
        }
        if (stopped) {
            d.release()
            return
        }
        decoder = d
        compositor.armSlot(slot)
        compositor.setTileAspect(slot, dims.width * dims.pixelAspect / dims.height)
        Log.i(TAG, "${camera.name}: ${c.name} ${dims.width}x${dims.height} par=${dims.pixelAspect} decoder=${d.codecName}" +
            if (d.keyframesOnly) " (key frames only)" else "")
        main.post { if (!stopped && decoder === d) setState(if (d.keyframesOnly) "stills" else "decoding") }
    }

    private fun waitForSurface(): Surface? {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!stopped && SystemClock.elapsedRealtime() < deadline) {
            compositor.surfaceFor(slot)?.let { if (it.isValid) return it }
            SystemClock.sleep(100)
        }
        return null
    }

    // ---- VideoDecoder.Listener (codec thread) ----

    override fun onFrameRendered(decoder: VideoDecoder) {
        if (live) return
        live = true
        main.post {
            if (!stopped && this.decoder === decoder) {
                retryDelayMs = INITIAL_RETRY_MS
                liveSince = SystemClock.elapsedRealtime()
                setState(if (decoder.keyframesOnly) "stills" else "live")
                onLive?.invoke()
            }
        }
    }

    override fun onOutputSize(decoder: VideoDecoder, width: Int, height: Int) {
        compositor.setTileAspect(slot, width * pixelAspect / height)
    }

    override fun onDecoderError(decoder: VideoDecoder, message: String) {
        main.post {
            if (!stopped && this.decoder === decoder) {
                setState("decoder error")
                scheduleReconnect("decoder error: $message")
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            val n = framesDecoded
            if (n != lastDecodedCount) {
                lastDecodedCount = n
                lastProgressAt = now
            }
            if (now - lastProgressAt > STALL_MS) {
                setState("stalled")
                scheduleReconnect("no frames for ${(now - lastProgressAt) / 1000}s")
                return
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private companion object {
        const val TAG = "OnvifViewer"
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
        const val WATCHDOG_MS = 5_000L
        const val STALL_MS = 30_000L
    }
}
