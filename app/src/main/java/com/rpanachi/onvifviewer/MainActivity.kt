package com.rpanachi.onvifviewer

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rpanachi.onvifviewer.engine.CameraStream
import com.rpanachi.onvifviewer.engine.DecoderBudget
import com.rpanachi.onvifviewer.engine.Engine
import com.rpanachi.onvifviewer.model.Camera
import com.rpanachi.onvifviewer.model.CameraStore
import com.rpanachi.onvifviewer.model.CamerasYaml
import com.rpanachi.onvifviewer.model.GridSpec
import com.rpanachi.onvifviewer.model.LayoutPresets
import com.rpanachi.onvifviewer.model.StreamUrls
import com.rpanachi.onvifviewer.ui.CameraGridLayout
import com.rpanachi.onvifviewer.ui.CameraTileView
import com.rpanachi.onvifviewer.ui.GlCompositorView
import com.rpanachi.onvifviewer.ui.ScaleMode
import kotlin.math.abs

/**
 * Full-screen camera wall. Tap a tile to enlarge it (tap again to go back), swipe left/right to change
 * page, swipe in from the top or bottom edge (or long-press) to show the control bar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: CameraStore
    private lateinit var root: FrameLayout
    private lateinit var compositor: GlCompositorView
    private lateinit var labelGrid: CameraGridLayout
    private lateinit var controls: LinearLayout
    private lateinit var controlRow: LinearLayout
    private lateinit var statsText: TextView
    private val streams = mutableListOf<CameraStream>()
    private var enabledCameras: List<Camera> = emptyList()
    private var single: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFramesDrawn = 0L
    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CameraStore(this)
        applyIntentExtras(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        compositor = GlCompositorView(this)
        labelGrid = CameraGridLayout(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(compositor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(labelGrid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        buildControls()
        setContentView(root)

        val detector = GestureDetector(this, gestures)
        root.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                single != null -> {
                    single = null
                    rebuild()
                }
                controls.visibility == View.VISIBLE -> hideControls()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        if (store.firstRun) {
            store.firstRun = false
            Toast.makeText(this, "Tap a camera to enlarge it, swipe to change page, swipe down from the top edge for settings", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntentExtras(intent)) {
            enabledCameras = store.cameras().filter { it.enabled }
            single = null
            rebuild()
        }
    }

    /**
     * Test / provisioning hooks:
     * `adb shell am start -n <pkg>/.MainActivity --es layout 4 --ei page 0 --es scale zoom --es import <file> --ez reset true`
     */
    private fun applyIntentExtras(intent: Intent?): Boolean {
        var changed = false
        if (intent?.getBooleanExtra("reset", false) == true) {
            store.save(store.loadBundled())
            changed = true
        }
        intent?.getStringExtra("import")?.let { path ->
            runCatching { CamerasYaml.parse(java.io.File(path).readText()) }
                .onSuccess { list ->
                    if (list.isNotEmpty()) {
                        store.save(list)
                        changed = true
                    }
                }
                .onFailure { Log.w(TAG, "import of $path failed: ${it.message}") }
        }
        intent?.getStringExtra("layout")?.let {
            if (LayoutPresets.all.any { p -> p.id == it }) {
                store.layoutId = it
                changed = true
            }
        }
        intent?.getStringExtra("scale")?.let {
            store.scaleMode = ScaleMode.byId(it).id
            if (::compositor.isInitialized) {
                compositor.scaleMode = ScaleMode.byId(it)
                refreshControls()
            }
        }
        if (intent?.hasExtra("page") == true) {
            store.page = intent.getIntExtra("page", 0)
            changed = true
        }
        return changed
    }

    override fun onStart() {
        super.onStart()
        enabledCameras = store.cameras().filter { it.enabled }
        single = null
        rebuild()
        handler.postDelayed(statsLogger, STATS_INTERVAL_MS)
        handler.postDelayed(statsUpdater, 1000)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        releaseAll()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val spec = currentSpec()
        labelGrid.spec = spec
        compositor.setGrid(spec)
    }

    // ---- grid state ----

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun currentSpec(): GridSpec =
        LayoutPresets.spec(if (single != null) "1" else store.layoutId, isLandscape)

    private fun perPage() = LayoutPresets.byId(store.layoutId).count

    private fun pageCount() = if (enabledCameras.isEmpty()) 1 else (enabledCameras.size + perPage() - 1) / perPage()

    private fun visibleCameras(): List<Camera> {
        single?.let { return listOf(it) }
        val page = store.page.coerceIn(0, pageCount() - 1)
        store.page = page
        return enabledCameras.drop(page * perPage()).take(perPage())
    }

    private fun rebuild() {
        releaseAll()
        labelGrid.removeAllViews()
        compositor.clearTiles()
        val spec = currentSpec()
        labelGrid.spec = spec
        compositor.setGrid(spec)
        compositor.scaleMode = ScaleMode.byId(store.scaleMode)
        val cams = visibleCameras()
        if (cams.isEmpty()) {
            labelGrid.spec = LayoutPresets.spec("1", isLandscape)
            compositor.setGrid(labelGrid.spec)
            labelGrid.addView(TextView(this).apply {
                text = "No cameras enabled.\nSwipe down from the top edge and open Settings."
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                textSize = 20f
            })
        } else {
            val interval = Engine.renderIntervalMs(cams.size)
            compositor.frameIntervalMs = interval
            val useMain = single != null && store.mainInSingle
            cams.forEachIndexed { i, cam ->
                val tile = CameraTileView(this).apply { showLabel = store.showLabels }
                labelGrid.addView(tile)
                val url = if (useMain) (StreamUrls.mainFor(cam) ?: cam.input) else cam.input
                val stream = CameraStream(cam, url, i, compositor, tile, if (cams.size == 1) 0L else interval)
                streams += stream
                stream.start()
            }
            Log.i(TAG, "showing ${cams.size} camera(s), layout=${store.layoutId}, page=${store.page + 1}/${pageCount()}, single=${single?.name}, scale=${store.scaleMode}, render every ${interval}ms")
        }
        refreshControls()
        if (single != null) showControls() else if (controls.visibility == View.VISIBLE) scheduleHide()
    }

    private fun releaseAll() {
        streams.forEach { it.stop() }
        streams.clear()
    }

    private fun changePage(delta: Int) {
        val n = pageCount()
        if (n <= 1) return
        store.page = Math.floorMod(store.page + delta, n)
        rebuild()
    }

    // ---- control bar ----

    private fun buildControls() {
        val pad = (6 * density).toInt()
        controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        statsText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(pad * 2, 0, pad * 2, pad)
            visibility = View.GONE
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB8000000.toInt())
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(controlRow)
            })
            addView(statsText)
            visibility = View.GONE
            // consume touches so the gesture detector underneath does not see them
            setOnTouchListener { _, _ -> true }
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun barButton(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener {
            scheduleHide()
            onClick(it)
        }
    }

    private fun refreshControls() {
        controlRow.removeAllViews()
        val s = single
        val scaleLabel = "Scale: " + ScaleMode.byId(store.scaleMode).label.substringBefore(" (")
        if (s == null) {
            controlRow.addView(barButton("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
            controlRow.addView(barButton("Layout: " + LayoutPresets.byId(store.layoutId).label) { anchor -> pickLayout(anchor) })
            controlRow.addView(barButton("◀") { changePage(-1) })
            controlRow.addView(TextView(this).apply {
                text = "Page ${store.page + 1}/${pageCount()}"
                setTextColor(Color.WHITE)
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            })
            controlRow.addView(barButton("▶") { changePage(1) })
            controlRow.addView(barButton(scaleLabel) { anchor -> pickScale(anchor) })
            controlRow.addView(barButton("Hide") { hideControls() })
            statsText.visibility = View.GONE
        } else {
            controlRow.addView(barButton("Back to grid") {
                single = null
                rebuild()
            })
            controlRow.addView(barButton("Camera settings") {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_CAMERA_ID, s.id))
            })
            controlRow.addView(barButton(scaleLabel) { anchor -> pickScale(anchor) })
            statsText.visibility = View.VISIBLE
            statsStream = null
            statsText.text = "${s.name}: connecting"
        }
    }

    private fun pickLayout(anchor: View) {
        PopupMenu(this, anchor).apply {
            LayoutPresets.all.forEachIndexed { i, p -> menu.add(0, i, i, p.label) }
            setOnMenuItemClickListener { item ->
                val preset = LayoutPresets.all[item.itemId]
                if (preset.id != store.layoutId) {
                    store.layoutId = preset.id
                    store.page = 0
                    rebuild()
                }
                true
            }
            show()
        }
    }

    private fun pickScale(anchor: View) {
        PopupMenu(this, anchor).apply {
            ScaleMode.entries.forEachIndexed { i, m -> menu.add(0, i, i, m.label) }
            setOnMenuItemClickListener { item ->
                val mode = ScaleMode.entries[item.itemId]
                store.scaleMode = mode.id
                compositor.scaleMode = mode
                refreshControls()
                true
            }
            show()
        }
    }

    private fun showControls() {
        refreshControls()
        controls.visibility = View.VISIBLE
        scheduleHide()
    }

    private fun hideControls() {
        handler.removeCallbacks(hideRunnable)
        if (single == null) controls.visibility = View.GONE
    }

    /** In the grid the bar hides itself after a while; in single view it stays. */
    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (single == null) handler.postDelayed(hideRunnable, CONTROLS_TIMEOUT_MS)
    }

    private val hideRunnable = Runnable { hideControls() }

    // ---- stream statistics shown in single view ----

    private var statsStream: CameraStream? = null
    private var statsAt = 0L
    private var statsDecoded = 0L
    private var statsShown = 0L
    private var statsBytes = 0L

    private val statsUpdater = object : Runnable {
        override fun run() {
            if (single != null) streams.firstOrNull()?.let { statsText.text = describe(it) }
            handler.postDelayed(this, 1000)
        }
    }

    private fun describe(st: CameraStream): CharSequence {
        val now = SystemClock.elapsedRealtime()
        if (statsStream !== st) {
            statsStream = st
            statsAt = now
            statsDecoded = st.framesDecoded
            statsShown = st.framesRendered
            statsBytes = st.bytesReceived
            return "${st.camera.name}: ${st.state}"
        }
        val dt = (now - statsAt) / 1000.0
        if (dt < 0.5) return statsText.text
        val decodeFps = (st.framesDecoded - statsDecoded) / dt
        val shownFps = (st.framesRendered - statsShown) / dt
        val mbps = (st.bytesReceived - statsBytes) * 8 / dt / 1e6
        statsAt = now
        statsDecoded = st.framesDecoded
        statsShown = st.framesRendered
        statsBytes = st.bytesReceived
        val decoder = st.decoderName?.let { if (st.keyframesOnly) "$it (key frames only)" else it } ?: "no decoder yet"
        val liveFor = if (st.liveSince > 0) formatDuration(now - st.liveSince) else "-"
        return "${st.camera.name} · ${st.videoInfo.ifEmpty { "stream not described yet" }} · $decoder\n" +
            "%s · decode %.1f fps · shown %.1f fps · %.2f Mbit/s · dropped %d · reconnects %d · live for %s"
                .format(st.state, decodeFps, shownFps, mbps, st.framesDropped, st.reconnects, liveFor)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---- gestures ----

    private val gestures = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (single != null) {
                single = null
                rebuild()
                return true
            }
            if (controls.visibility == View.VISIBLE) {
                hideControls()
                return true
            }
            val idx = labelGrid.childIndexAt(e.x, e.y)
            if (idx in streams.indices) {
                single = streams[idx].camera
                rebuild()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            showControls()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null) return false
            val edge = EDGE_DP * density
            val fromTop = e1.y < edge && e2.y - e1.y > edge
            val fromBottom = e1.y > root.height - edge && e1.y - e2.y > edge
            if (fromTop || fromBottom) {
                if (controls.visibility != View.VISIBLE) showControls()
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || single != null) return false
            val edge = EDGE_DP * density
            if (e1.y < edge || e1.y > root.height - edge) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > SWIPE_MIN_PX && abs(velocityX) > SWIPE_MIN_VELOCITY && abs(dx) > abs(dy)) {
                changePage(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val statsLogger = object : Runnable {
        override fun run() {
            if (streams.isNotEmpty()) {
                val drawn = compositor.framesDrawn
                val fps = (drawn - lastFramesDrawn) * 1000 / STATS_INTERVAL_MS
                lastFramesDrawn = drawn
                Log.i(TAG, "stats: compositor ${fps}fps ${compositor.timing}, hw=${DecoderBudget.activeHardware()} | " + streams.joinToString(" | ") {
                    "${it.camera.name}[${it.state}, ${it.decoderName ?: "-"}${if (it.keyframesOnly) "/kf" else ""}, dec=${it.framesDecoded}, shown=${it.framesRendered}, drop=${it.framesDropped}]"
                })
            }
            handler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    companion object {
        const val TAG = "OnvifViewer"
        private const val STATS_INTERVAL_MS = 10_000L
        private const val CONTROLS_TIMEOUT_MS = 8_000L
        private const val SWIPE_MIN_PX = 120f
        private const val SWIPE_MIN_VELOCITY = 300f
        private const val EDGE_DP = 32f
    }
}
