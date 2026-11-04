package com.rpanachi.onvifviewer.model

object StreamUrls {
    private val credentials = Regex("//[^/@]*@")

    /** Hides user:password in a URL for display and logs. */
    fun mask(url: String): String = url.replace(credentials, "//***@")

    /** Best-effort main-stream URL derived from a sub-stream URL, or null when the vendor pattern is unknown. */
    fun deriveMain(sub: String): String? {
        val dahua = Regex("subtype=1\\b")
        val hik = Regex("/Streaming/Channels/(\\d)02\\b")
        val tplink = Regex("/stream2\\b")
        return when {
            dahua.containsMatchIn(sub) -> sub.replace(dahua, "subtype=0")
            sub.contains("_sub", ignoreCase = true) -> sub.replace("_sub", "_main", ignoreCase = true)
            hik.containsMatchIn(sub) -> sub.replace(hik, "/Streaming/Channels/\$101")
            tplink.containsMatchIn(sub) -> sub.replace(tplink, "/stream1")
            else -> null
        }
    }

    fun mainFor(camera: Camera): String? = camera.main.takeIf { it.isNotBlank() } ?: deriveMain(camera.input)
}
