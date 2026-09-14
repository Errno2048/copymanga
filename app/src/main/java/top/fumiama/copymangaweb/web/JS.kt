package top.fumiama.copymangaweb.web

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import top.fumiama.copymangaweb.activity.MainActivity.Companion.wm
import top.fumiama.copymangaweb.activity.ViewMangaActivity
import top.fumiama.copymangaweb.activity.ViewNovelActivity
import top.fumiama.copymangaweb.tool.NovelDownloader
import top.fumiama.copymangaweb.tool.ReadingProgress
import java.io.File
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
    // 反色方式（value / rgb）：回传给原生阅读器
    @JavascriptInterface
    fun setInvertStyle(style: String) {
        val ctx = wm?.get() ?: return
        ctx.getSharedPreferences(NIGHT_PREF, Context.MODE_PRIVATE).edit().putString(INVERT_STYLE_KEY, style).apply()
    }

    companion object {
        const val NIGHT_PREF = "night"
        const val NIGHT_KEY = "on"
        const val INVERT_KEY = "invert_mode"
        const val INVERT_STYLE_KEY = "invert_style"
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

    /**
     * 最近一次读的小说卷（JSON；没有返回空串）。详情页据此把按钮文案改成「續看 <卷名>」，
     * 点击即回到该卷上次的位置。
     */
    @JavascriptInterface
    fun lastNovelVolume(bookName: String): String {
        val ctx = wm?.get() ?: return ""
        val prog = ReadingProgress.novel(ctx, bookName) ?: return ""
        return NovelStore.gson().toJson(prog)
    }

    /**
     * 某本漫画最近一次阅读：优先本地已下载的记录（zip:<漫画名>），否则用在线记录。
     * 返回 {chapterId, chapterName, page, local, comicName}；没有进度返回空串。
     */
    @JavascriptInterface
    fun lastComicChapter(pathWord: String, comicName: String): String {
        val ctx = wm?.get() ?: return ""
        val local = comicName.takeIf { it.isNotBlank() }
            ?.let { ReadingProgress.comic(ctx, "zip:$it") }
        val online = pathWord.takeIf { it.isNotBlank() }
            ?.let { ReadingProgress.comic(ctx, it) }
        val p = local ?: online ?: return ""
        val o = com.google.gson.JsonObject()
        o.addProperty("chapterId", p.chapterId)
        o.addProperty("chapterName", p.chapterName)
        o.addProperty("page", p.page)
        o.addProperty("total", p.total)
        o.addProperty("local", p === local)
        o.addProperty("comicName", comicName)
        return o.toString()
    }

    /** 打开已下载的漫画章节（本地 zip）：按 <漫画名>/<话>/<话>.zip 定位。 */
    @JavascriptInterface
    fun openLocalComic(comicName: String, zipName: String): Boolean {
        val ctx = wm?.get() ?: return false
        val root = File(ctx.getExternalFilesDir(""), comicName)
        val dir = root.listFiles()?.firstOrNull { d ->
            d.isDirectory && d.listFiles()?.any { it.isFile && it.name == zipName } == true
        } ?: return false
        val files = dir.listFiles()?.toList().orEmpty()
        val zip = files.firstOrNull { it.isFile && it.name == zipName } ?: return false
        val zips = files.filter { it.isFile && it.extension.equals("zip", true) }
        ViewMangaActivity.zipFile = zip
        ViewMangaActivity.titleText = zip.name
        ViewMangaActivity.zipPosition = zips.indexOf(zip)
        ViewMangaActivity.zipList = zips.toTypedArray()
        ViewMangaActivity.cd = dir
        ViewMangaActivity.nextChapterUrl = null
        ViewMangaActivity.previousChapterUrl = null
        ctx.startActivity(android.content.Intent(ctx, ViewMangaActivity::class.java))
        return true
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