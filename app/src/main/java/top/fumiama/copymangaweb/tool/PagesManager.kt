package top.fumiama.copymangaweb.tool

import android.widget.Toast
import top.fumiama.copymangaweb.activity.MainActivity.Companion.wm
import top.fumiama.copymangaweb.activity.ViewMangaActivity
import java.lang.ref.WeakReference

class PagesManager(w: WeakReference<ViewMangaActivity>) {
    private val activity = w
    private val v get() = activity.get()
    private var isEndL = false
    private var isEndR = false
    fun toPreviousPage(){ toPage(v?.r2l==true) }
    fun toNextPage(){ toPage(v?.r2l!=true) }
    fun goBackward() = toPage(false)
    fun goForward() = toPage(true)
    private fun judgePrevious() = (v?.pageNum ?: 0) > 1
    private fun judgeNext() = (v?.pageNum ?: 0) < (v?.count ?: 0)
    private fun toPage(goNext:Boolean){
        if (v?.clicked == false) {
            if (if(goNext)judgeNext() else judgePrevious()) {
                if(goNext) {
                    v?.scrollForward()
                    isEndR = false
                } else {
                    v?.scrollBack()
                    isEndL = false
                }
            } else {
                if (v?.dlZip2View == true) {
                    switchZipChapter(goNext)
                } else {
                    val chapterUrl = if(goNext) ViewMangaActivity.nextChapterUrl else ViewMangaActivity.previousChapterUrl
                    if (chapterUrl == null) {
                        showReachedEnd()
                        return
                    }
                    if (if(goNext)isEndR else isEndL) {
                        // 直接切换到相邻章节：旧实现依赖点击可见 WebView 里站点的
                        // comicControlBottomTopClick 按钮，但详情页直连阅读器后那个页面已不在。
                        v?.gotoAdjacentChapter(goNext)
                    } else doubleTapToast(goNext)
                }
            }
        } else v?.hideSettings()
    }

    private fun switchZipChapter(goNext: Boolean) {
        if (!(if (goNext) isEndR else isEndL)) {
            doubleTapToast(goNext)
            return
        }
        v?.gotoAdjacentChapter(goNext)
    }

    private fun showReachedEnd() {
        Toast.makeText(v?.applicationContext, "已经到头了~", Toast.LENGTH_SHORT).show()
    }

    fun manageInfo(){
        if (v?.clicked == false) v?.showSettings() else v?.hideSettings()
    }
    private fun doubleTapToast(goNext: Boolean){
        val hint = if(goNext) "下" else "上"
        Toast.makeText(
            v?.applicationContext,
            "再次按下加载${hint}一章",
            Toast.LENGTH_SHORT
        ).show()
        if(goNext) isEndR = true
        else isEndL = true
    }
}