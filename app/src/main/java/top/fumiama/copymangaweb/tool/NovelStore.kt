package top.fumiama.copymangaweb.tool

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.Charset

/**
 * 一个目录条目（站点 contents 的一项）：
 * - type = 1 正文：在整卷 txt 中的 0 基行区间 [start, end)，end 不含下一章标题；
 * - type = 2 插图：正文里的插画，imageUrl 为原图链接（txt 里没有它）。
 */
class NovelChapterMeta {
    var name: String = ""
    var start: Int = 0
    var end: Int = 0
    var type: Int = 1
    var imageUrl: String = ""

    val isImage: Boolean get() = type == 2
}

/** 一卷：一个整篇 txt + 章节行区间。 */
class NovelVolumeMeta {
    var id: String = ""
    var name: String = ""
    var index: Int = 0
    var txtAddr: String = ""
    var encoding: String = "GBK"
    var prev: String? = null
    var next: String? = null
    var chapters: MutableList<NovelChapterMeta> = mutableListOf()
}

class NovelVolumeInfo {
    var id: String = ""
    var name: String = ""
}

/** 一本书的本地元数据：卷清单 + 已获取的卷详情。 */
class NovelBookMeta {
    var pathWord: String = ""
    var name: String = ""
    var apiBase: String = ""
    /** 封面原图地址（页面侧传入；用于「我的下载」的封面展示） */
    var cover: String = ""
    var author: String = ""
    var volumes: MutableList<NovelVolumeInfo> = mutableListOf()
    var details: MutableMap<String, NovelVolumeMeta> = mutableMapOf()

    fun volumeName(id: String): String = volumes.firstOrNull { it.id == id }?.name.orEmpty()
    fun volumeIndex(id: String): Int = volumes.indexOfFirst { it.id == id }
}

/** JS 侧打开阅读器 / 下载时传入的请求体。 */
class NovelOpenRequest {
    var pathWord: String = ""
    var name: String = ""
    var apiBase: String = ""
    var cover: String = ""
    var author: String = ""
    var volumes: MutableList<NovelVolumeInfo> = mutableListOf()
    var volume: NovelVolumeMeta? = null
}

/**
 * 小说本地存储：与漫画下载并列放在 getExternalFilesDir("")/<书名>/ 下，
 * 元数据存 novel.json，每卷正文按卷名存 <卷名>.txt（保持原始编码字节）。
 */
object NovelStore {
    const val META_FILE = "novel.json"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun gson(): Gson = gson

    fun dir(ctx: Context, name: String): File = File(ctx.getExternalFilesDir(""), name)
    private fun metaFile(ctx: Context, name: String): File = File(dir(ctx, name), META_FILE)
    fun txtFile(ctx: Context, name: String, volumeName: String): File =
        File(dir(ctx, name), sanitize(volumeName) + ".txt")

    /** 插图的本地文件：<书名>/<卷名>__<条目名>.<ext> */
    fun imageFile(ctx: Context, name: String, volumeName: String, chapter: NovelChapterMeta): File {
        val ext = chapter.imageUrl.substringAfterLast('.', "jpg")
            .substringBefore('?').take(4).ifBlank { "jpg" }
        return File(dir(ctx, name), sanitize("${volumeName}__${chapter.name}") + "." + ext)
    }

    /** 封面文件：<书名>/cover.jpg（下载时落盘，已存在则不重下） */
    fun coverFile(ctx: Context, name: String): File = File(dir(ctx, name), "cover.jpg")

    fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "volume" }

    fun load(ctx: Context, name: String): NovelBookMeta? {
        val f = metaFile(ctx, name)
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), NovelBookMeta::class.java) }.getOrNull()
    }

    fun save(ctx: Context, meta: NovelBookMeta) {
        val d = dir(ctx, meta.name)
        if (!d.exists()) d.mkdirs()
        metaFile(ctx, meta.name).writeText(gson.toJson(meta))
    }

    /** 把 JS 传来的卷清单/卷详情并入本地元数据。 */
    fun merge(ctx: Context, req: NovelOpenRequest): NovelBookMeta {
        val meta = load(ctx, req.name) ?: NovelBookMeta().also {
            it.name = req.name
            it.pathWord = req.pathWord
        }
        if (req.pathWord.isNotBlank()) meta.pathWord = req.pathWord
        if (req.apiBase.isNotBlank()) meta.apiBase = req.apiBase
        if (req.cover.isNotBlank()) meta.cover = req.cover
        if (req.author.isNotBlank()) meta.author = req.author
        if (req.volumes.isNotEmpty()) meta.volumes = req.volumes
        req.volume?.let { meta.details[it.id] = it }
        save(ctx, meta)
        return meta
    }

    fun volumeText(ctx: Context, meta: NovelBookMeta, vol: NovelVolumeMeta): String? {
        val f = txtFile(ctx, meta.name, vol.name)
        if (!f.exists()) return null
        return runCatching {
            f.readBytes().toString(Charset.forName(vol.encoding.ifBlank { "GBK" }))
        }.getOrNull()
    }

    fun isVolumeLocal(ctx: Context, bookName: String, volumeId: String): Boolean {
        val meta = load(ctx, bookName) ?: return false
        val vol = meta.details[volumeId] ?: return false
        return txtFile(ctx, bookName, vol.name).exists()
    }

    /** 章节正文：把整卷按行切开后取 [start, end)。 */
    fun chapterText(full: String, chapter: NovelChapterMeta): String {
        val lines = full.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val s = chapter.start.coerceIn(0, lines.size)
        val e = chapter.end.coerceIn(s, lines.size)
        return lines.subList(s, e).joinToString("\n").trim('\n')
    }
}
