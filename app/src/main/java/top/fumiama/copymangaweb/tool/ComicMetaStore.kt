package top.fumiama.copymangaweb.tool

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import top.fumiama.copymangaweb.web.JS
import java.io.File
import java.util.concurrent.Executors

/** 漫画的元信息：下载章节时顺手存下来，供「我的下载」按书架样式展示。 */
class ComicMeta {
    var pathWord: String = ""
    var name: String = ""
    /** 封面原图地址（页面侧已补成绝对地址） */
    var cover: String = ""
    var author: String = ""
    var type: String = ""
}

/**
 * 漫画元信息存储（纯本地）：
 * - 页面侧（i.js 在详情页抓到）经 [top.fumiama.copymangaweb.web.JS.rememberComicMeta] 存进
 *   SharedPreferences（按 pathWord 索引）；
 * - 本地已有该漫画目录时，顺手补写 `<漫画目录>/meta.json` 并下载 `cover.jpg`：
 *   老下载也能被补全，不需要重新下载章节；
 * - 已下载过的封面不重复下载（以文件是否存在为准）。
 */
object ComicMetaStore {
    private const val PREF = "comic_meta"
    private const val PREFIX = "meta_"
    const val META_FILE = "meta.json"
    const val COVER_FILE = "cover.jpg"
    private val gson = Gson()
    private val io = Executors.newSingleThreadExecutor()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(ctx: Context, pathWord: String): ComicMeta? {
        if (pathWord.isBlank()) return null
        val raw = prefs(ctx).getString(PREFIX + pathWord, null) ?: return null
        return runCatching { gson.fromJson(raw, ComicMeta::class.java) }.getOrNull()
    }

    fun readFromDir(dir: File): ComicMeta? {
        val f = File(dir, META_FILE)
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), ComicMeta::class.java) }.getOrNull()
    }

    fun coverFile(dir: File): File = File(dir, COVER_FILE)

    fun writeInto(dir: File, meta: ComicMeta) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, META_FILE).writeText(gson.toJson(meta))
        }
    }

    /** 存下页面侧给的元信息；本地已有目录则补写 meta.json 并补下封面。 */
    fun remember(ctx: Context, json: String) {
        val meta = runCatching { gson.fromJson(json, ComicMeta::class.java) }.getOrNull() ?: return
        if (meta.pathWord.isBlank()) return
        val mine = ComicMeta().also {
            it.pathWord = meta.pathWord
            it.name = meta.name
            it.cover = meta.cover
            it.author = meta.author
            it.type = meta.type
        }
        val old = get(ctx, mine.pathWord)
        if (old != null) {
            if (mine.name.isBlank()) mine.name = old.name
            if (mine.cover.isBlank()) mine.cover = old.cover
            if (mine.author.isBlank()) mine.author = old.author
            if (mine.type.isBlank()) mine.type = old.type
        }
        prefs(ctx).edit().putString(PREFIX + mine.pathWord, gson.toJson(mine)).apply()
        // 已下载的漫画：补写 meta.json + 封面（已存在就不重下）
        val dir = findLocalDir(ctx, mine)
        if (dir != null) {
            writeInto(dir, mine)
            ensureCover(dir, mine)
        }
        Log.d(
            "ComicMeta",
            "remember ${mine.pathWord} name=${mine.name} author=${mine.author} cover=${mine.cover}"
        )
    }

    /**
     * 找这个 pathWord 对应的本地漫画目录：
     * 1) 下载时记下的 pathWord -> 漫画名 映射最可靠（下载页会用这个映射）；
     * 2) 其次按名字完全一致找；
     * 3) 最后退一步：目录名与漫画名互相包含（PC 端与手机端标题偶有细微差异）。
     */
    private fun findLocalDir(ctx: Context, meta: ComicMeta): File? {
        val root = ctx.getExternalFilesDir("") ?: return null
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        val mapped = ctx.getSharedPreferences(JS.NIGHT_PREF, Context.MODE_PRIVATE)
            .getString(JS.COMIC_NAME_PREFIX + meta.pathWord, null)
        if (!mapped.isNullOrBlank()) {
            dirs.firstOrNull { it.name == mapped }?.let { return it }
        }
        if (meta.name.isNotBlank()) {
            dirs.firstOrNull { it.name == meta.name }?.let { return it }
            val short = meta.name.take(6)
            if (short.length >= 4) {
                dirs.firstOrNull { it.name.contains(short) || meta.name.contains(it.name) }
                    ?.let { return it }
            }
        }
        return null
    }

    /** 下载封面到 `<漫画目录>/cover.jpg`（已存在则跳过）。 */
    fun ensureCover(dir: File, meta: ComicMeta?) {
        val url = meta?.cover.orEmpty()
        if (url.isBlank()) return
        val dest = coverFile(dir)
        if (dest.exists() && dest.length() > 0) return
        io.execute {
            val ok = NovelApi.downloadBinary(url, dest)
            Log.d("ComicMeta", "cover download ${if (ok) "ok" else "fail"} -> ${dest.path}")
        }
    }
}
