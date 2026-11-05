package com.rpanachi.onvifviewer

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.rpanachi.onvifviewer.model.Camera
import com.rpanachi.onvifviewer.model.CameraStore
import com.rpanachi.onvifviewer.model.CamerasYaml
import com.rpanachi.onvifviewer.model.LayoutPresets
import com.rpanachi.onvifviewer.model.StreamUrls
import com.rpanachi.onvifviewer.ui.ScaleMode
import java.io.File

/** Layout choice, viewer options and the camera list (add / edit / delete / reorder / import / export). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: CameraStore
    private val cameras = mutableListOf<Camera>()
    private lateinit var list: LinearLayout
    private val dp: Float get() = resources.displayMetrics.density

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CameraStore(this)
        cameras.addAll(store.cameras())
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(header("Layout in landscape"))
        root.addView(layoutSpinner(true))
        root.addView(header("Layout in portrait"))
        root.addView(layoutSpinner(false))
        root.addView(header("Video scaling"))
        val modes = ScaleMode.entries
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
            setSelection(modes.indexOf(ScaleMode.byId(store.scaleMode)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    store.scaleMode = modes[position].id
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        root.addView(toggle("Show camera names", store.showLabels) { store.showLabels = it })
        root.addView(toggle("Switch to the main (high resolution) stream when a camera is shown full screen", store.mainInSingle) { store.mainInSingle = it })

        root.addView(header("Cameras"))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Add") { editCamera(null) })
        buttons.addView(button("Import YAML") {
            try {
                importLauncher.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                toast("No file picker available on this device")
            }
        })
        buttons.addView(button("Export YAML") { export() })
        buttons.addView(button("Reset") {
            confirm("Replace the camera list with the list bundled in the app?") { replaceAll(store.loadBundled()) }
        })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(TextView(this).apply {
            text = "Viewer gestures: tap a camera to enlarge it (tap again to go back), swipe left/right to change page, swipe in from the top or bottom edge (or long-press) to show the control bar."
            setPadding(0, pad, 0, pad)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshList()
        intent.getStringExtra(EXTRA_CAMERA_ID)?.let { id ->
            val index = cameras.indexOfFirst { it.id == id }
            if (index >= 0) editCamera(index)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
    }

    private val layoutSpinners = HashMap<Boolean, Spinner>()

    /** One layout per orientation; an orientation that was never set follows the other one. */
    private fun layoutSpinner(landscape: Boolean): Spinner {
        val presets = LayoutPresets.forOrientation(landscape)
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, presets.map { it.label })
            setSelection(presets.indexOfFirst { it.id == store.layoutId(landscape) }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (store.layoutId(landscape) == presets[position].id) return
                    store.setLayoutId(landscape, presets[position].id)
                    store.page = 0
                    val others = LayoutPresets.forOrientation(!landscape)
                    layoutSpinners[!landscape]?.setSelection(others.indexOfFirst { it.id == store.layoutId(!landscape) }.coerceAtLeast(0))
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            layoutSpinners[landscape] = this
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        this.text = text
        isChecked = checked
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        list.removeAllViews()
        if (cameras.isEmpty()) {
            list.addView(TextView(this).apply { text = "No cameras. Add one or import a YAML file." })
            return
        }
        cameras.forEachIndexed { i, cam ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(CheckBox(this).apply {
                isChecked = cam.enabled
                setOnCheckedChangeListener { _, c ->
                    cameras[i] = cameras[i].copy(enabled = c)
                    persist()
                }
            })
            row.addView(TextView(this).apply {
                text = "${cam.name}\n${StreamUrls.mask(cam.input)}"
                val p = (8 * dp).toInt()
                setPadding(p, p, p, p)
                setOnClickListener { editCamera(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("▲") { move(i, -1) })
            row.addView(button("▼") { move(i, 1) })
            list.addView(row)
        }
    }

    private fun move(i: Int, delta: Int) {
        val j = i + delta
        if (j < 0 || j >= cameras.size) return
        val c = cameras.removeAt(i)
        cameras.add(j, c)
        persist()
        refreshList()
    }

    private fun persist() = store.save(cameras)

    private fun replaceAll(newList: List<Camera>) {
        cameras.clear()
        cameras.addAll(newList)
        store.page = 0
        persist()
        refreshList()
    }

    private fun editCamera(index: Int?) {
        val existing = index?.let { cameras[it] }
        val pad = (16 * dp).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, uri: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val name = field("Name", existing?.name ?: "")
        val input = field("RTSP sub stream URL (rtsp://user:pass@host:554/...)", existing?.input ?: "rtsp://", true)
        val main = field("RTSP main stream URL (optional)", existing?.main ?: "", true)
        val vendor = field("Vendor / model (optional)", existing?.vendor ?: "")
        listOf(name, input, main, vendor).forEach { form.addView(it) }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add camera" else "Edit camera")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (index != null && existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                confirm("Delete ${existing.name}?") {
                    cameras.removeAt(index)
                    persist()
                    refreshList()
                }
            }
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val url = input.text.toString().trim()
            if (n.isEmpty()) {
                toast("Name is required")
                return@setOnClickListener
            }
            if (!url.startsWith("rtsp://", ignoreCase = true) || url.length < 10) {
                toast("The stream URL must start with rtsp://")
                return@setOnClickListener
            }
            val cam = Camera(
                id = existing?.id ?: Camera.uniqueId(Camera.slug(n), cameras.map { it.id }.toSet()),
                name = n,
                enabled = existing?.enabled ?: true,
                vendor = vendor.text.toString().trim(),
                input = url,
                main = main.text.toString().trim(),
            )
            if (index == null) cameras.add(cam) else cameras[index] = cam
            persist()
            refreshList()
            dialog.dismiss()
        }
    }

    private fun importFrom(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.getOrElse {
            toast("Could not read file: ${it.message}")
            return
        }
        val parsed = runCatching { CamerasYaml.parse(text) }.getOrElse {
            toast("Invalid camera file: ${it.message}")
            return
        }
        if (parsed.isEmpty()) {
            toast("No cameras found in the file")
            return
        }
        confirm("Replace the current list with ${parsed.size} camera(s) from the file?") { replaceAll(parsed) }
    }

    private fun export() {
        try {
            exportLauncher.launch("cameras.yaml")
        } catch (e: ActivityNotFoundException) {
            val f = File(getExternalFilesDir(null), "cameras.yaml")
            f.writeText(CamerasYaml.dump(cameras))
            toast("Saved to ${f.absolutePath}")
        }
    }

    private fun exportTo(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(CamerasYaml.dump(cameras).toByteArray()) }
        }.onSuccess { toast("Camera list exported") }
            .onFailure { toast("Export failed: ${it.message}") }
    }
}
