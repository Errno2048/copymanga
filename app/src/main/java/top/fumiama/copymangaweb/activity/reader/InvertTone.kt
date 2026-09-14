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
 *
 * 反色方式有两种（见 STYLE_*）：
 * - STYLE_RGB：按通道反色（r,g,b -> 255-r,...）。对纯灰度页等价于只反转亮度，
 *   但彩色像素的色相会被换成补色（红 -> 青），画面颜色失真。
 * - STYLE_VALUE（默认）：只反转亮度，色相与饱和度保持。做法是取 HSV 的 V = max(r,g,b)，
 *   令 V' = comp(1 - V)，再把三个通道同乘 V'/V。因为 max 通道恰好映射到 V'，
 *   所以不会溢出裁剪，且 S = (V-min)/V 在变换前后严格相等（H 也严格不变）。
 *   对纯灰像素 (r=g=b=V)，结果退化为 comp(1-V)，与 STYLE_RGB 的查表结果逐像素相同，
 *   因此原本判定为黑白页的页面显示完全不变。
 *
 * 之所以不用 Lab 的 L*：sRGB->XYZ->Lab 需要 cbrt/pow 逐步换算，单页 200 万像素代价过高，
 * 而且固定 a/b 反色 L* 会大量落到色域外、必须裁剪，反而破坏颜色。HSV 的 V 只取一个
 * max、一次乘法即可，且天然无色域问题。
 */
object InvertTone {
    const val MIN_GAIN = 1.0f
    const val MAX_GAIN = 2.5f

    const val MAX_BLACK = 0.15f

    /** 只反转亮度、保持色相与饱和度（默认）。 */
    const val STYLE_VALUE = "value"

    /** 按通道直接反色（彩色像素会变成补色）。 */
    const val STYLE_RGB = "rgb"

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

    /**
     * 亮度反色用的缩放系数表：out_c = in_c * scale[V]，V = max(r,g,b)。
     *
     * scale[V] = comp(1 - V) / V；V = 0（纯黑）时目标为白，用 0 作哨兵，
     * 由 applyValue 特判成白色（避免 0/0）。
     */
    fun buildValueScale(blackPoint: Float, gain: Float): FloatArray {
        val b = blackPoint.coerceIn(0f, 0.9f)
        val g = gain.coerceIn(MIN_GAIN, 4f)
        val inv = 1.0 / g.toDouble()
        val span = (1f - b).coerceAtLeast(1e-3f)
        val out = FloatArray(256)
        for (v in 0..255) {
            val lv = v / 255f
            if (lv <= 0f) {
                out[v] = 0f
                continue
            }
            val k = 1f - lv
            val kk = ((k - b) / span).coerceIn(0f, 1f).toDouble()
            val target = Math.pow(kk, inv).toFloat()
            out[v] = target / lv
        }
        return out
    }

    /**
     * 只反转亮度：色相、饱和度保持（纯灰像素结果与 apply 的查表一致）。
     * 同样不修改入参位图。
     */
    fun applyValue(src: Bitmap, scale: FloatArray): Bitmap {
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
                if (a == 0) {
                    row[x] = 0
                    continue
                }
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val v = maxOf(r, g, b)
                if (v == 0) {
                    // 纯黑 -> 纯白（保持不透明）
                    row[x] = (a shl 24) or 0xFFFFFF
                    continue
                }
                val k = scale[v]
                val nr = (r * k).toInt().coerceIn(0, 255)
                val ng = (g * k).toInt().coerceIn(0, 255)
                val nb = (b * k).toInt().coerceIn(0, 255)
                row[x] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}
