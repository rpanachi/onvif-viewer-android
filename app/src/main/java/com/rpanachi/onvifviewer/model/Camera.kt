package com.rpanachi.onvifviewer.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** One camera. [input] is the RTSP sub stream shown in the grid; [main] is an optional high-resolution stream. */
data class Camera(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val vendor: String = "",
    val input: String,
    val main: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("vendor", vendor)
        .put("input", input)
        .put("main", main)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.optString("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            vendor = o.optString("vendor"),
            input = o.optString("input"),
            main = o.optString("main"),
        )

        fun listToJson(list: List<Camera>): String {
            val a = JSONArray()
            list.forEach { a.put(it.toJson()) }
            return a.toString()
        }

        fun listFromJson(json: String): List<Camera> {
            val a = JSONArray(json)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }

        /** ASCII, lowercase, underscore-separated identifier derived from a display name. */
        fun slug(text: String): String {
            val ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val s = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            return s.ifEmpty { "camera" }
        }

        fun uniqueId(base: String, taken: Set<String>): String {
            var id = base
            var n = 2
            while (id in taken) id = "${base}_${n++}"
            return id
        }
    }
}
