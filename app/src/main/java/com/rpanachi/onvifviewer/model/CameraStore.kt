package com.rpanachi.onvifviewer.model

import android.content.Context

/** Persists the camera list and viewer preferences. The bundled cameras.yaml seeds the list on first run. */
class CameraStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("onvif_viewer", Context.MODE_PRIVATE)

    fun cameras(): List<Camera> {
        val json = prefs.getString(KEY_CAMERAS, null)
        if (json != null) return runCatching { Camera.listFromJson(json) }.getOrDefault(emptyList())
        val bundled = loadBundled()
        save(bundled)
        return bundled
    }

    fun save(list: List<Camera>) {
        prefs.edit().putString(KEY_CAMERAS, Camera.listToJson(list)).apply()
    }

    fun loadBundled(): List<Camera> = runCatching {
        app.assets.open("cameras.yaml").bufferedReader().use { CamerasYaml.parse(it.readText()) }
    }.getOrDefault(emptyList())

    /**
     * The layout chosen for an orientation. Until one is chosen there, it follows the choice made in
     * the other orientation (or the single layout stored by older versions).
     */
    fun layoutId(landscape: Boolean): String {
        val own = prefs.getString(if (landscape) KEY_LAYOUT_LAND else KEY_LAYOUT_PORT, null)
        LayoutPresets.resolve(own, landscape)?.let { return it }
        val other = prefs.getString(if (landscape) KEY_LAYOUT_PORT else KEY_LAYOUT_LAND, null)
            ?: prefs.getString(KEY_LAYOUT, null)
        return LayoutPresets.resolve(other, landscape) ?: LayoutPresets.defaultId(landscape)
    }

    fun setLayoutId(landscape: Boolean, id: String) {
        prefs.edit().putString(if (landscape) KEY_LAYOUT_LAND else KEY_LAYOUT_PORT, id).apply()
    }

    var page: Int
        get() = prefs.getInt(KEY_PAGE, 0)
        set(v) = prefs.edit().putInt(KEY_PAGE, v).apply()

    /** "fit", "stretch" or "zoom" (see ScaleMode). */
    var scaleMode: String
        get() = prefs.getString(KEY_SCALE, "stretch") ?: "stretch"
        set(v) = prefs.edit().putString(KEY_SCALE, v).apply()

    var showLabels: Boolean
        get() = prefs.getBoolean(KEY_LABELS, true)
        set(v) = prefs.edit().putBoolean(KEY_LABELS, v).apply()

    var mainInSingle: Boolean
        get() = prefs.getBoolean(KEY_MAIN_SINGLE, true)
        set(v) = prefs.edit().putBoolean(KEY_MAIN_SINGLE, v).apply()

    var firstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(v) = prefs.edit().putBoolean(KEY_FIRST_RUN, v).apply()

    private companion object {
        const val KEY_CAMERAS = "cameras"
        const val KEY_LAYOUT = "layout"
        const val KEY_LAYOUT_LAND = "layout_landscape"
        const val KEY_LAYOUT_PORT = "layout_portrait"
        const val KEY_PAGE = "page"
        const val KEY_SCALE = "scale_mode"
        const val KEY_LABELS = "show_labels"
        const val KEY_MAIN_SINGLE = "main_in_single"
        const val KEY_FIRST_RUN = "first_run"
    }
}
