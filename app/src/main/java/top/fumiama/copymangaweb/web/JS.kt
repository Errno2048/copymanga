package top.fumiama.copymangaweb.web

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import top.fumiama.copymangaweb.activity.MainActivity.Companion.wm
import top.fumiama.copymangaweb.activity.ViewMangaActivity

class JS {
    @JavascriptInterface
    fun loadComic(url: String) {
        val pc = Mirrors.detailPc
        // 去掉 query/fragment 与末尾斜杠，避免 chapter id 被参数或空片段污染
        val clean = url.substringBefore('#').substringBefore('?').trimEnd('/')
        val u = when {
            pc == null -> ""
            clean.contains("/details/comic/") ->
                "$pc/${clean.substringAfter("/details/comic/").trim('/')}"
            clean.contains("/comicContent/") -> {
                val segs = clean.substringAfter("/comicContent/").split('/').filter { it.isNotEmpty() }
                if (segs.size < 2) "" else "$pc/${segs.first()}/chapter/${segs.last()}"
            }
            else -> ""
        }
        Log.d("MyJS", "Load comic: $u")
        when {
            u.isEmpty() -> Log.w("MyJS", "Unrecognized comic url, ignored: $url")
            u.contains("/chapter/") -> wm?.get()?.openStreamingManga(u)
            else -> wm?.get()?.loadHiddenUrl(u)
        }
    }
    // 从详情页直接拉起阅读器（跳过内容页）：
    // 可见 WebView 未发生导航，退出阅读器时需跳过 goBack，否则会多退一页。
    @JavascriptInterface
    fun loadComicDirect(url: String) {
        ViewMangaActivity.skipBackOnExit = true
        loadComic(url)
    }
    // 夜间模式状态回传给原生端（漫画阅读器据此把背景改为黑色）
    @JavascriptInterface
    fun setNightMode(on: Boolean) {
        val ctx = wm?.get() ?: return
        ctx.getSharedPreferences(NIGHT_PREF, Context.MODE_PRIVATE).edit().putBoolean(NIGHT_KEY, on).apply()
    }
    // 黑白漫画反色模式（off / auto / on）：回传给原生阅读器
    @JavascriptInterface
    fun setInvertMode(mode: String) {
        val ctx = wm?.get() ?: return
        ctx.getSharedPreferences(NIGHT_PREF, Context.MODE_PRIVATE).edit().putString(INVERT_KEY, mode).apply()
    }
    companion object {
        const val NIGHT_PREF = "night"
        const val NIGHT_KEY = "on"
        const val INVERT_KEY = "invert_mode"
        const val INVERT_GAIN_KEY = "invert_gain"
        const val INVERT_BLACK_KEY = "invert_black"
    }
    @JavascriptInterface
    fun hideFab() {
        wm?.get()?.hideFab()
    }
    @JavascriptInterface
    fun enterProfile(){
        wm?.get()?.setFab2DlList()
    }
}