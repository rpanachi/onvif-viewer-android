package com.rpanachi.onvifviewer.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shared threads for all camera streams. */
object Engine {
    private val codecThread = HandlerThread("codec-callbacks", Process.THREAD_PRIORITY_DISPLAY).apply { start() }

    /** All MediaCodec callbacks run here; the work per frame is a small buffer copy. */
    val codecHandler: Handler = Handler(codecThread.looper)

    /** MediaCodec.release() can take 100-300 ms, so releases run off the main thread, and in parallel. */
    val releaseExecutor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "codec-release") }

    /** Display refresh budget per tile count: fewer tiles get a smoother picture. */
    fun renderIntervalMs(tiles: Int): Long = when {
        tiles <= 1 -> 33L
        tiles <= 4 -> 50L
        tiles <= 9 -> 66L
        else -> 83L
    }
}
