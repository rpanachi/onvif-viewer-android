package com.rpanachi.onvifviewer.engine

/** Helpers for Annex-B byte streams (NAL units separated by 00 00 01 / 00 00 00 01 start codes). */
object AnnexB {
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun nalUnits(data: ByteArray, size: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i + 2 < size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var end = i
                    if (end > start && data[end - 1].toInt() == 0) end--
                    if (end > start) out += data.copyOfRange(start, end)
                }
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start in 0 until size) out += data.copyOfRange(start, size)
        return out
    }

    fun nalType(nal: ByteArray, hevc: Boolean): Int =
        if (hevc) (nal[0].toInt() shr 1) and 0x3F else nal[0].toInt() and 0x1F

    fun isParameterSet(nal: ByteArray, hevc: Boolean): Boolean {
        if (nal.isEmpty()) return false
        val t = nalType(nal, hevc)
        return if (hevc) t in 32..34 else (t == 7 || t == 8)
    }
}
