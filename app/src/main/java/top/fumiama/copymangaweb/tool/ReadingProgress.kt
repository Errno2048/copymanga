package top.fumiama.copymangaweb.tool

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 本地阅读进度。
 *
 * 官方渠道的浏览记录是登录后由服务端生成的，网页封装的客户端没有这个能力（站点的浏览记录
 * 接口只有 GET，没有写入接口）。这里做一份本地记录：读到哪儿就存哪儿，再次打开同一章节
 * （漫画）或同一本书（小说）时回到该位置。
 */
object ReadingProgress {
    private const val PREF = "reading_progress"
    private const val KEY_COMIC = "comic_"
    private const val KEY_NOVEL = "novel_"

    class ComicProgress(
        val pathWord: String,
        val chapterId: String,
        val chapterName: String,
        val page: Int,
        val total: Int
    )

    class NovelProgress(
        val volumeId: String,
        val volumeName: String,
        val chapterIndex: Int,
        val chapterName: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun saveComic(
        context: Context,
        pathWord: String,
        chapterId: String,
        chapterName: String,
        page: Int,
        total: Int
    ) {
        if (pathWord.isBlank() || chapterId.isBlank() || page <= 0) return
        val obj = JsonObject().apply {
            addProperty("pathWord", pathWord)
            addProperty("chapterId", chapterId)
            addProperty("chapterName", chapterName)
            addProperty("page", page)
            addProperty("total", total)
            addProperty("at", System.currentTimeMillis())
        }
        prefs(context).edit().putString(KEY_COMIC + pathWord, obj.toString()).apply()
    }

    fun comic(context: Context, pathWord: String): ComicProgress? {
        if (pathWord.isBlank()) return null
        val raw = prefs(context).getString(KEY_COMIC + pathWord, null) ?: return null
        return runCatching {
            val o = JsonParser.parseString(raw).asJsonObject
            ComicProgress(
                o.get("pathWord")?.asString.orEmpty(),
                o.get("chapterId")?.asString.orEmpty(),
                o.get("chapterName")?.asString.orEmpty(),
                o.get("page")?.asInt ?: 0,
                o.get("total")?.asInt ?: 0
            )
        }.getOrNull()
    }

    fun saveNovel(
        context: Context,
        book: String,
        volumeId: String,
        volumeName: String,
        chapterIndex: Int,
        chapterName: String
    ) {
        if (book.isBlank() || volumeId.isBlank()) return
        val obj = JsonObject().apply {
            addProperty("volumeId", volumeId)
            addProperty("volumeName", volumeName)
            addProperty("chapterIndex", chapterIndex)
            addProperty("chapterName", chapterName)
            addProperty("at", System.currentTimeMillis())
        }
        prefs(context).edit().putString(KEY_NOVEL + book, obj.toString()).apply()
    }

    fun novel(context: Context, book: String): NovelProgress? {
        if (book.isBlank()) return null
        val raw = prefs(context).getString(KEY_NOVEL + book, null) ?: return null
        return runCatching {
            val o = JsonParser.parseString(raw).asJsonObject
            NovelProgress(
                o.get("volumeId")?.asString.orEmpty(),
                o.get("volumeName")?.asString.orEmpty(),
                o.get("chapterIndex")?.asInt ?: 0,
                o.get("chapterName")?.asString.orEmpty()
            )
        }.getOrNull()
    }
}
