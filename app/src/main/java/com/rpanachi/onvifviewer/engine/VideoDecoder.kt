package com.rpanachi.onvifviewer.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Keeps track of how many hardware decoder instances are in use per MIME type, so a tile beyond the
 * SoC's limit (8 on the Fire HD 8) goes to a software decoder in key-frame-only mode instead of
 * failing or starving the others.
 */
object DecoderBudget {
    private val active = HashMap<String, Int>()

    fun hardwareLimit(mime: String, hw: List<MediaCodecInfo>): Int =
        hw.maxOfOrNull { info ->
            runCatching { info.getCapabilitiesForType(mime).maxSupportedInstances }.getOrDefault(0)
        }?.takeIf { it > 0 } ?: 8

    /** Waits briefly for a hardware slot to free up (releases happen asynchronously). */
    fun tryAcquireHardware(mime: String, limit: Int, waitMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (true) {
            synchronized(this) {
                val n = active[mime] ?: 0
                if (n < limit) {
                    active[mime] = n + 1
                    return true
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return false
            SystemClock.sleep(50)
        }
    }

    @Synchronized
    fun releaseHardware(mime: String) {
        active[mime] = ((active[mime] ?: 1) - 1).coerceAtLeast(0)
    }

    @Synchronized
    fun activeHardware(): Map<String, Int> = HashMap(active)

    fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        val n = info.name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg")
    }

    fun decoders(mime: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
        }
}

/**
 * Asynchronous MediaCodec wrapper fed with Annex-B access units. Output goes straight to [surface];
 * frames are rendered at most every [renderIntervalUs] of stream time, the rest are decoded but not
 * displayed (they are still needed as references).
 */
class VideoDecoder(
    private val mime: String,
    private val dims: VideoDims,
    private val parameterSets: List<ByteArray>,
    private val surface: Surface,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrameRendered(decoder: VideoDecoder)
        fun onOutputSize(decoder: VideoDecoder, width: Int, height: Int)
        fun onDecoderError(decoder: VideoDecoder, message: String)
    }

    @Volatile var renderIntervalUs = 0L
    var codecName = ""
        private set
    var keyframesOnly = false
        private set
    private var hardware = false

    @Volatile var framesDecoded = 0L
        private set
    @Volatile var framesRendered = 0L
        private set
    @Volatile var framesDropped = 0L
        private set

    private var codec: MediaCodec? = null
    private val lock = Any()
    private val queue = ArrayDeque<AccessUnit>()
    private val freeInputs = ArrayDeque<Int>()
    private var waitingForKeyframe = true
    private var nextRenderPtsUs = Long.MIN_VALUE
    @Volatile private var released = false

    /** Hardware decoders first (within the budget), then software in key-frame-only mode. */
    fun start(): Boolean {
        val all = DecoderBudget.decoders(mime)
        val (sw, hw) = all.partition { DecoderBudget.isSoftware(it) }
        val limit = DecoderBudget.hardwareLimit(mime, hw)
        val candidates = ArrayList<MediaCodecInfo>()
        if (hw.isNotEmpty() && DecoderBudget.tryAcquireHardware(mime, limit, HARDWARE_WAIT_MS)) {
            candidates += hw
            hardware = true
        }
        candidates += sw
        for (info in candidates) {
            val isSw = DecoderBudget.isSoftware(info)
            if (isSw && hardware) { // hardware candidates all failed: give the budget back
                DecoderBudget.releaseHardware(mime)
                hardware = false
            }
            val mc = try {
                MediaCodec.createByCodecName(info.name)
            } catch (e: Exception) {
                Log.w(TAG, "${info.name}: create failed: ${e.message}")
                continue
            }
            try {
                mc.setCallback(callback, handler)
                mc.configure(buildFormat(), surface, null, 0)
                synchronized(lock) { codec = mc }
                mc.start()
            } catch (e: Exception) {
                Log.w(TAG, "${info.name}: start failed: ${e.message}")
                synchronized(lock) { codec = null; freeInputs.clear() }
                runCatching { mc.release() }
                continue
            }
            codecName = info.name
            keyframesOnly = isSw
            return true
        }
        if (hardware) {
            DecoderBudget.releaseHardware(mime)
            hardware = false
        }
        return false
    }

    private fun buildFormat(): MediaFormat {
        val f = MediaFormat.createVideoFormat(mime, dims.width, dims.height)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(256 * 1024, dims.width * dims.height))
        if (parameterSets.isNotEmpty()) {
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                val csd = parameterSets.fold(ByteArray(0)) { acc, nal -> acc + AnnexB.START_CODE + nal }
                f.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            } else {
                parameterSets.firstOrNull { (it[0].toInt() and 0x1F) == 7 }?.let { f.setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.START_CODE + it)) }
                parameterSets.firstOrNull { (it[0].toInt() and 0x1F) == 8 }?.let { f.setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.START_CODE + it)) }
            }
        }
        return f
    }

    fun submit(au: AccessUnit) {
        synchronized(lock) {
            if (released || codec == null) return
            if (keyframesOnly && !au.keyframe && !au.hasParameterSets) return
            if (queue.size >= MAX_QUEUE) {
                framesDropped += queue.size
                queue.clear()
                waitingForKeyframe = true
            }
            if (waitingForKeyframe) {
                if (!au.keyframe) {
                    framesDropped++
                    return
                }
                waitingForKeyframe = false
            }
            queue.addLast(au)
            drainLocked()
        }
    }

    private fun drainLocked() {
        val mc = codec ?: return
        while (freeInputs.isNotEmpty() && queue.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val au = queue.removeFirst()
            val buf = try {
                mc.getInputBuffer(index)
            } catch (e: IllegalStateException) {
                return
            } ?: continue
            buf.clear()
            if (au.size > buf.remaining()) {
                Log.w(TAG, "$codecName: access unit of ${au.size} bytes exceeds input buffer (${buf.remaining()})")
                freeInputs.addFirst(index)
                waitingForKeyframe = true
                framesDropped++
                continue
            }
            buf.put(au.data, 0, au.size)
            try {
                mc.queueInputBuffer(index, 0, au.size, au.timestampUs, if (au.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (e: IllegalStateException) {
                return
            }
        }
    }

    private fun shouldRender(ptsUs: Long): Boolean {
        val iv = renderIntervalUs
        if (iv <= 0 || keyframesOnly) return true
        if (nextRenderPtsUs == Long.MIN_VALUE || ptsUs < nextRenderPtsUs - iv) {
            nextRenderPtsUs = ptsUs + iv
            return true
        }
        if (ptsUs >= nextRenderPtsUs) {
            nextRenderPtsUs += iv
            if (ptsUs - nextRenderPtsUs > iv) nextRenderPtsUs = ptsUs + iv
            return true
        }
        return false
    }

    fun release() {
        val mc: MediaCodec?
        synchronized(lock) {
            released = true
            mc = codec
            codec = null
            queue.clear()
            freeInputs.clear()
        }
        if (mc != null) {
            runCatching { mc.stop() }
            runCatching { mc.release() }
        }
        if (hardware) {
            DecoderBudget.releaseHardware(mime)
            hardware = false
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            synchronized(lock) {
                if (released || codec !== mc) return
                freeInputs.addLast(index)
                drainLocked()
            }
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) { if (released || codec !== mc) return }
            val render = shouldRender(info.presentationTimeUs)
            try {
                mc.releaseOutputBuffer(index, render)
            } catch (e: IllegalStateException) {
                return
            }
            framesDecoded++
            if (render) {
                framesRendered++
                listener.onFrameRendered(this@VideoDecoder)
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            if (!released) listener.onDecoderError(this@VideoDecoder, e.diagnosticInfo)
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            var w = format.getInteger(MediaFormat.KEY_WIDTH)
            var h = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
                w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            }
            if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            }
            if (w > 0 && h > 0) listener.onOutputSize(this@VideoDecoder, w, h)
        }
    }

    private companion object {
        const val TAG = "OnvifViewer"
        const val MAX_QUEUE = 60
        /** How long a new stream waits for a hardware decoder slot (previous tiles release theirs asynchronously). */
        const val HARDWARE_WAIT_MS = 4_000L
    }
}
