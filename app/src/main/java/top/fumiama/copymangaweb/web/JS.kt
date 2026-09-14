package top.fumiama.copymangaweb.web

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import top.fumiama.copymangaweb.activity.MainActivity.Companion.wm
import top.fumiama.copymangaweb.activity.ViewMangaActivity
import top.fumiama.copymangaweb.activity.ViewNovelActivity
import top.fumiama.copymangaweb.tool.NovelDownloader
import top.fumiama.copymangaweb.tool.NovelOpenRequest
import top.fumiama.copymangaweb.tool.NovelStore

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
    // ---------- 小说 ----------

    /** 打开原生小说阅读器。metaJson 由页面侧（i.js）从站点接口取好后传入。 */
    @JavascriptInterface
    fun openNovelReader(metaJson: String) {
        val ctx = wm?.get() ?: return
        val req = runCatching {
            NovelStore.gson().fromJson(metaJson, NovelOpenRequest::class.java)
        }.getOrNull() ?: return
        if (req.name.isBlank()) return
        NovelStore.merge(ctx, req)
        ctx.startActivity(
            Intent(ctx, ViewNovelActivity::class.java)
                .putExtra(ViewNovelActivity.EXTRA_BOOK, req.name)
                .putExtra(ViewNovelActivity.EXTRA_VOLUME, req.volume?.id)
        )
    }

    /** 整本下载小说（后台逐卷下载正文）。 */
    @JavascriptInterface
    fun downloadNovel(metaJson: String) {
        val ctx = wm?.get() ?: return
        val req = runCatching {
            NovelStore.gson().fromJson(metaJson, NovelOpenRequest::class.java)
        }.getOrNull() ?: return
        if (req.name.isBlank()) return
        NovelDownloader.start(ctx, req)
    }

    /** 该卷是否已在本地：同步返回，供页面在点击时决定走原生阅读器还是网页在线阅读。 */
    @JavascriptInterface
    fun isNovelVolumeLocal(bookName: String, volumeId: String): Boolean {
        val ctx = wm?.get() ?: return false
        return NovelStore.isVolumeLocal(ctx, bookName, volumeId)
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