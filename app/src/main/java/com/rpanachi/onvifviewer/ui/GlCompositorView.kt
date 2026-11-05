package com.rpanachi.onvifviewer.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.rpanachi.onvifviewer.model.GridGeometry
import com.rpanachi.onvifviewer.model.GridSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One SurfaceView for the whole camera wall. Each decoder renders into its own SurfaceTexture (a GPU
 * texture in this process); a render thread draws all tiles into the single window surface at a
 * capped rate, so SurfaceFlinger composes one layer instead of one per camera.
 */
enum class ScaleMode(val id: String, val label: String) {
    FIT("fit", "Fit (keep proportions)"),
    /** Fills the cell. Only a camera shown full screen falls back to fitting, see [FULL_SCREEN_MAX_STRETCH]. */
    STRETCH("stretch", "Stretch to fill"),
    ZOOM("zoom", "Zoom to fill (crop)");

    companion object {
        /**
         * A camera shown full screen is stretched only up to this distortion (4:3 video on a 16:9 screen
         * is 1.33). Beyond it, typically a landscape camera on a portrait screen, it keeps its proportions.
         */
        const val FULL_SCREEN_MAX_STRETCH = 1.4f

        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: STRETCH
    }
}

class GlCompositorView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private inner class Slot(val index: Int) {
        var texId = 0
        var surfaceTexture: SurfaceTexture? = null
        @Volatile var surface: Surface? = null
        var lastTimestamp = -1L
        @Volatile var hasFrame = false
        /** Frames count only once the current stream's decoder is attached, so a stale one is never shown. */
        @Volatile var armed = false
        @Volatile var aspect = 0f
        val matrix = FloatArray(16)
    }

    private val slots = Array(MAX_SLOTS) { Slot(it) }
    /** The grid plus, optionally, which slot each cell shows (cell i shows slot i when null). */
    private class Layout(val spec: GridSpec, val slotMap: IntArray?)

    @Volatile private var layout: Layout? = null
    @Volatile var scaleMode = ScaleMode.STRETCH
    /** In stretch mode, a picture that would be distorted by more than this factor is fitted instead. */
    @Volatile var maxStretch = Float.MAX_VALUE
    @Volatile var frameIntervalMs = 66L
    @Volatile var framesDrawn = 0L
        private set
    /** Average milliseconds per drawn frame spent in updateTexImage / draw / swap (for the stats log). */
    @Volatile var timing = ""
        private set
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var dirty = true
    private var renderThread: RenderThread? = null
    private val gapPx = GridGeometry.gapPx(resources.displayMetrics.density)

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = RenderThread(holder.surface).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderThread?.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderThread?.shutdown()
        renderThread = null
    }

    /** The Surface a decoder should render tile [slot] into, or null until the GL thread is up. */
    fun surfaceFor(slot: Int): Surface? = slots.getOrNull(slot)?.surface

    /** [slotMap] lets a cell show any slot, e.g. the single view showing the tile that was tapped. */
    fun setGrid(spec: GridSpec, slotMap: IntArray? = null) {
        layout = Layout(spec, slotMap)
        wake()
    }

    fun setTileAspect(slot: Int, aspect: Float) {
        slots.getOrNull(slot)?.let {
            it.aspect = aspect
            wake()
        }
    }

    /** Forget the last frames so a re-used tile does not show the previous camera. */
    fun clearTiles() {
        for (s in slots) {
            s.armed = false
            s.hasFrame = false
        }
        wake()
    }

    fun clearTile(slot: Int) {
        slots.getOrNull(slot)?.let {
            it.armed = false
            it.hasFrame = false
        }
        wake()
    }

    /** Called when a new decoder starts rendering into [slot]: from now on its frames are shown. */
    fun armSlot(slot: Int) {
        slots.getOrNull(slot)?.armed = true
    }

    /** True once the compositor holds a frame of the stream currently rendering into [slot]. */
    fun hasFrame(slot: Int): Boolean = slots.getOrNull(slot)?.hasFrame == true

    private fun wake() {
        lock.withLock {
            dirty = true
            changed.signalAll()
        }
    }

    private inner class RenderThread(private val window: Surface) : Thread("gl-compositor") {
        @Volatile private var running = true
        @Volatile private var width = 0
        @Volatile private var height = 0
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var aPos = 0
        private var aTex = 0
        private var uMatrix = 0
        private var uTexture = 0
        private val posBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val texBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        fun resize(w: Int, h: Int) {
            width = w
            height = h
            wake()
        }

        fun shutdown() {
            running = false
            wake()
            runCatching { join(3000) }
        }

        override fun run() {
            try {
                initEgl()
                initGl()
                createSlots()
                loop()
            } catch (e: Exception) {
                Log.e(TAG, "compositor failed", e)
            } finally {
                teardown()
            }
        }

        private fun loop() {
            var nextFrameAt = SystemClock.uptimeMillis()
            var tUpdate = 0L
            var tDraw = 0L
            var tSwap = 0L
            var frames = 0
            var lastReport = SystemClock.uptimeMillis()
            while (running) {
                // Fixed-rate tick: polling the textures is cheaper than per-frame callbacks from 16 decoders.
                val now = SystemClock.uptimeMillis()
                if (now < nextFrameAt) {
                    lock.withLock { if (running && !dirty) changed.await(nextFrameAt - now, TimeUnit.MILLISECONDS) }
                    if (!running) break
                }
                val tick = SystemClock.uptimeMillis()
                nextFrameAt = maxOf(nextFrameAt + frameIntervalMs, tick + frameIntervalMs / 2)
                var redraw = lock.withLock { val d = dirty; dirty = false; d }
                val t0 = SystemClock.elapsedRealtimeNanos()
                for (s in slots) {
                    val st = s.surfaceTexture ?: continue
                    if (s.surface == null) continue
                    st.updateTexImage()
                    val ts = st.timestamp
                    if (ts != s.lastTimestamp) {
                        s.lastTimestamp = ts
                        if (s.armed) { // otherwise a late frame of the stream that used this slot before
                            st.getTransformMatrix(s.matrix)
                            s.hasFrame = true
                            redraw = true
                        }
                    }
                }
                val t1 = SystemClock.elapsedRealtimeNanos()
                if (!redraw || width == 0 || height == 0) continue
                draw()
                val t2 = SystemClock.elapsedRealtimeNanos()
                EGL14.eglSwapBuffers(display, eglSurface)
                val t3 = SystemClock.elapsedRealtimeNanos()
                framesDrawn++
                frames++
                tUpdate += t1 - t0
                tDraw += t2 - t1
                tSwap += t3 - t2
                if (tick - lastReport >= 10_000 && frames > 0) {
                    timing = "update=%.2fms draw=%.2fms swap=%.2fms".format(tUpdate / 1e6 / frames, tDraw / 1e6 / frames, tSwap / 1e6 / frames)
                    tUpdate = 0; tDraw = 0; tSwap = 0; frames = 0
                    lastReport = tick
                }
            }
        }

        private fun draw() {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val lay = layout ?: return
            val g = lay.spec
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aTex)
            val mode = scaleMode
            for (i in 0 until minOf(g.count, MAX_SLOTS)) {
                val s = slots.getOrNull(lay.slotMap?.getOrNull(i) ?: i) ?: continue
                if (!s.hasFrame) continue
                val r = GridGeometry.cellRect(g, i, width, height, gapPx)
                var l = r.left.toFloat()
                var t = r.top.toFloat()
                var rt = r.right.toFloat()
                var b = r.bottom.toFloat()
                val aspect = s.aspect
                var u0 = 0f
                var u1 = 1f
                var v0 = 0f
                var v1 = 1f
                val tw = rt - l
                val th = b - t
                if (aspect > 0f && tw > 0f && th > 0f) {
                    val tileAspect = tw / th
                    val distortion = if (aspect > tileAspect) aspect / tileAspect else tileAspect / aspect
                    when (if (mode == ScaleMode.STRETCH && distortion > maxStretch) ScaleMode.FIT else mode) {
                        ScaleMode.FIT -> if (aspect > tileAspect) { // black bars above and below
                            val nh = tw / aspect
                            t += (th - nh) / 2f
                            b = t + nh
                        } else {
                            val nw = th * aspect
                            l += (tw - nw) / 2f
                            rt = l + nw
                        }
                        ScaleMode.ZOOM -> if (aspect > tileAspect) { // crop left and right
                            val f = tileAspect / aspect
                            u0 = (1f - f) / 2f
                            u1 = (1f + f) / 2f
                        } else {
                            val f = aspect / tileAspect
                            v0 = (1f - f) / 2f
                            v1 = (1f + f) / 2f
                        }
                        ScaleMode.STRETCH -> Unit
                    }
                }
                texBuf.clear()
                texBuf.put(floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1))
                texBuf.position(0)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
                val x0 = l / width * 2f - 1f
                val x1 = rt / width * 2f - 1f
                val y0 = 1f - b / height * 2f
                val y1 = 1f - t / height * 2f
                posBuf.clear()
                posBuf.put(floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y1))
                posBuf.position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
                GLES20.glUniformMatrix4fv(uMatrix, 1, false, s.matrix, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }

        private fun initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("no EGL display")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("eglInitialize failed")
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) throw RuntimeException("no EGL config")
            val config = configs[0]!!
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
            eglSurface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) throw RuntimeException("eglMakeCurrent failed")
            // Our own tick paces the output; do not let the driver block (or spin) on vsync as well.
            EGL14.eglSwapInterval(display, 0)
        }

        private fun initGl() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) throw RuntimeException("program link failed: " + GLES20.glGetProgramInfoLog(program))
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTexture = GLES20.glGetUniformLocation(program, "sTexture")
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) throw RuntimeException("shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            return shader
        }

        private fun createSlots() {
            val ids = IntArray(MAX_SLOTS)
            GLES20.glGenTextures(MAX_SLOTS, ids, 0)
            for (s in slots) {
                s.texId = ids[s.index]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val st = SurfaceTexture(s.texId)
                s.surfaceTexture = st
                s.hasFrame = false
                s.lastTimestamp = -1L
                s.surface = Surface(st)
            }
        }

        private fun teardown() {
            for (s in slots) {
                s.surface?.let {
                    s.surface = null
                    it.release()
                }
                s.surfaceTexture?.let {
                    s.surfaceTexture = null
                    it.release()
                }
                s.hasFrame = false
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
        }
    }

    companion object {
        const val MAX_SLOTS = 16
        private const val TAG = "OnvifViewer"
        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(sTexture, vTex);
            }
        """
    }
}
