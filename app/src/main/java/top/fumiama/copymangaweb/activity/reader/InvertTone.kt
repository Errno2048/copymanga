package top.fumiama.copymangaweb.activity.reader

import android.graphics.Bitmap

/**
 * 反色后的线条补偿。
 *
 * 视觉变细的成因是 gamma：sRGB 的中间灰只对应约 21% 的实际光强，所以白线四周的
 * 抗锯齿「半墨」像素在黑底上落到感知阈值以下、不再被算作线条。补偿就是把半墨提亮。
 *
 * 采用 Levels 中间段（两个参数，含义独立）：
 *
 *   k     = 1 - v                    // 墨量：纸=0，实墨=1
 *   k'    = clamp((k - b) / (1 - b)) // 黑场 b：压掉纸面残留，使背景保持纯黑
 *   v_out = k' ^ (1 / g)             // 线条增益 g：把半墨提成线，等效加粗
 *
 * 端点固定（k=0 -> 0、k=1 -> 1）、单调、平滑、不裁剪，网点层次得以保留。
 * g = 1 且 b = 0 时退化为纯反色，即未启用补偿。
 *
 * 两个参数相互独立、各有用途：实测把黑场与增益自动耦合反而会抵消补偿效果
 * （黑场会把想要提亮的边缘微光一起削掉），因此分开控制，黑场默认 0。
 */
object InvertTone {
    const val MIN_GAIN = 1.0f
    const val MAX_GAIN = 2.5f

    const val MAX_BLACK = 0.15f

    fun buildLut(blackPoint: Float, gain: Float): IntArray {
        val b = blackPoint.coerceIn(0f, 0.9f)
        val g = gain.coerceIn(MIN_GAIN, 4f)
        val inv = 1.0 / g.toDouble()
        val span = (1f - b).coerceAtLeast(1e-3f)
        val lut = IntArray(256)
        for (v in 0..255) {
            val k = 1f - v / 255f
            val kk = ((k - b) / span).coerceIn(0f, 1f).toDouble()
            lut[v] = (Math.pow(kk, inv) * 255.0).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** 返回应用了 LUT 的**新**位图；不修改入参，避免污染 Glide 的缓存位图。 */
    fun apply(src: Bitmap, lut: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val a = p ushr 24
                if (a == 0) { row[x] = 0; continue }
                val r = lut[(p shr 16) and 0xFF]
                val gg = lut[(p shr 8) and 0xFF]
                val bb = lut[p and 0xFF]
                row[x] = (a shl 24) or (r shl 16) or (gg shl 8) or bb
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}
