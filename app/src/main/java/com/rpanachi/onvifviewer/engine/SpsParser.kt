package com.rpanachi.onvifviewer.engine

/** Picture size and pixel aspect ratio taken from an H.264 or H.265 sequence parameter set. */
data class VideoDims(val width: Int, val height: Int, val pixelAspect: Float = 1f)

/** MSB-first bit reader with Exp-Golomb support. */
class BitReader(private val data: ByteArray) {
    private var pos = 0

    fun bit(): Int {
        if (pos ushr 3 >= data.size) throw IllegalArgumentException("end of data")
        val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
        pos++
        return b
    }

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or bit() }
        return v
    }

    fun skip(n: Int) {
        pos += n
    }

    fun ue(): Int {
        var zeros = 0
        while (bit() == 0) {
            zeros++
            if (zeros > 31) throw IllegalArgumentException("bad exp-golomb code")
        }
        return if (zeros == 0) 0 else (1 shl zeros) - 1 + bits(zeros)
    }

    fun se(): Int {
        val k = ue()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
}

object SpsParser {

    /** Strips emulation-prevention bytes (00 00 03 -> 00 00) starting at [offset]. */
    fun unescape(nal: ByteArray, offset: Int): ByteArray {
        val out = ByteArray(nal.size - offset)
        var n = 0
        var zeros = 0
        for (i in offset until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[n++] = nal[i]
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    private val sarTable = floatArrayOf(
        1f, 1f, 12f / 11, 10f / 11, 16f / 11, 40f / 33, 24f / 11, 20f / 11, 32f / 11,
        80f / 33, 18f / 11, 15f / 11, 64f / 33, 160f / 99, 4f / 3, 3f / 2, 2f,
    )

    private val highProfiles = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** [sps] is the H.264 SPS NAL unit including its 1-byte header, without start code. */
    fun parseH264(sps: ByteArray): VideoDims {
        val r = BitReader(unescape(sps, 1))
        val profileIdc = r.bits(8)
        r.skip(16) // constraint flags + level_idc
        r.ue() // seq_parameter_set_id
        var chromaFormatIdc = 1
        if (profileIdc in highProfiles) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) r.skip(1)
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (r.bit() == 1) {
                val count = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0 until count) if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                val n = r.ue()
                repeat(n) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.skip(1)
        r.skip(1) // direct_8x8_inference_flag
        var cropL = 0
        var cropR = 0
        var cropT = 0
        var cropB = 0
        if (r.bit() == 1) {
            cropL = r.ue()
            cropR = r.ue()
            cropT = r.ue()
            cropB = r.ue()
        }
        var par = 1f
        if (r.bit() == 1 && r.bit() == 1) { // vui_parameters_present, aspect_ratio_info_present
            val idc = r.bits(8)
            par = if (idc == 255) {
                val w = r.bits(16)
                val h = r.bits(16)
                if (h > 0 && w > 0) w.toFloat() / h else 1f
            } else {
                sarTable.getOrElse(idc) { 1f }
            }
        }
        val subW = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
        val subH = if (chromaFormatIdc == 1) 2 else 1
        val cropUnitX = if (chromaFormatIdc == 0) 1 else subW
        val cropUnitY = (if (chromaFormatIdc == 0) 1 else subH) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropL + cropR) * cropUnitX
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropT + cropB) * cropUnitY
        if (width <= 0 || height <= 0) throw IllegalArgumentException("bad SPS size")
        return VideoDims(width, height, par)
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                val delta = r.se()
                next = (last + delta + 256) % 256
            }
            last = if (next == 0) last else next
        }
    }

    /** [sps] is the H.265 SPS NAL unit including its 2-byte header, without start code. */
    fun parseH265(sps: ByteArray): VideoDims {
        val r = BitReader(unescape(sps, 2))
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1) // sps_temporal_id_nesting_flag
        // profile_tier_level(1, maxSubLayersMinus1): general part is 96 bits
        r.skip(96)
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.bit() == 1
            subLevel[i] = r.bit() == 1
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chromaFormatIdc = r.ue()
        if (chromaFormatIdc == 3) r.skip(1)
        var width = r.ue()
        var height = r.ue()
        if (r.bit() == 1) { // conformance_window_flag
            val subW = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val subH = if (chromaFormatIdc == 1) 2 else 1
            val l = r.ue()
            val rr = r.ue()
            val t = r.ue()
            val b = r.ue()
            width -= (l + rr) * subW
            height -= (t + b) * subH
        }
        if (width <= 0 || height <= 0) throw IllegalArgumentException("bad SPS size")
        return VideoDims(width, height)
    }
}
