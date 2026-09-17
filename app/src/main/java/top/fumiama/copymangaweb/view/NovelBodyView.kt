package top.fumiama.copymangaweb.view

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * 滚动模式的正文视图：直接绘制整章那一份 [Layout]。
 *
 * 关键点是与分页模式**共用同一个 Layout 对象**。以前滚动模式用 TextView，它按
 * textAppearance / fallbackLineSpacing / letterSpacing 等主题属性自己造一份 Layout，
 * 于是同一段文字在两条链路上可能排出不同的行距与折行——在部分机型上滚动模式的行距
 * 会比分页模式更宽，页首偏移（按分页 Layout 算的 pageTops）就对不上，误差逐行累积，
 * 越靠后的页偏差越大。共用一份 Layout 后这些差异从根上不存在。
 */
class NovelBodyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var body: Layout? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val l = body
        val inner = w - paddingLeft - paddingRight
        if (l != null && inner > 0 && l.width != inner) {
            // 宽度不同折行就不同，页<->滚动偏移的对应关系会整体错位
            Log.w("NovelBody", "layout width ${l.width} != view inner width $inner")
        }
        val h = (l?.height ?: 0) + paddingTop + paddingBottom
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = body ?: return
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        l.draw(canvas)
        canvas.restore()
    }
}
