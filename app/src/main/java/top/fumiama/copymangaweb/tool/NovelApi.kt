package top.fumiama.copymangaweb.tool

import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小说接口访问。卷清单/卷详情也可由页面侧（i.js）取好传来，
 * 这里保留直连能力，用于阅读器内部切换相邻卷。
 */
object NovelApi {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; DCO-AL00) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/110.0.0.0 Mobile Safari/537.36"

    private fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
        }

    fun httpGetText(url: String, timeoutMs: Int = 20000): String? {
        var c: HttpURLConnection? = null
        return try {
            c = open(url, timeoutMs)
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    /** 下载正文（原样字节，保持站点编码），成功返回 true。 */
    fun downloadBinary(url: String, dest: File, timeoutMs: Int = 30000): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = open(url, timeoutMs)
            if (c.responseCode !in 200..299) return false
            val parent = dest.parentFile ?: return false
            if (!parent.exists()) parent.mkdirs()
            val tmp = File(parent, dest.name + ".tmp")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        } catch (e: Exception) {
            false
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    fun volumes(apiBase: String, pathWord: String): List<NovelVolumeInfo> {
        val body = httpGetText("$apiBase/api/v3/book/$pathWord/volumes") ?: return emptyList()
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("code")?.asInt != 200) return emptyList()
            val list = root.getAsJsonObject("results")?.getAsJsonArray("list") ?: return emptyList()
            list.map { e ->
                val o = e.asJsonObject
                NovelVolumeInfo().also {
                    it.id = o.get("id")?.asString.orEmpty()
                    it.name = o.get("name")?.asString.orEmpty()
                }
            }
        }.getOrDefault(emptyList())
    }

    fun volumeDetail(apiBase: String, pathWord: String, volumeId: String): NovelVolumeMeta? {
        val body = httpGetText("$apiBase/api/v3/book/$pathWord/volume/$volumeId") ?: return null
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("code")?.asInt != 200) return null
            val v = root.getAsJsonObject("results")?.getAsJsonObject("volume") ?: return null
            NovelVolumeMeta().also { m ->
                m.id = v.get("id")?.asString ?: volumeId
                m.name = v.get("name")?.asString.orEmpty()
                m.index = v.get("index")?.asInt ?: 0
                m.txtAddr = v.get("txt_addr")?.asString.orEmpty()
                m.encoding = v.get("txt_encoding")?.asString ?: "GBK"
                m.prev = v.get("prev")?.takeIf { !it.isJsonNull }?.asString
                m.next = v.get("next")?.takeIf { !it.isJsonNull }?.asString
                v.getAsJsonArray("contents")?.forEach { e ->
                    val o = e.asJsonObject
                    m.chapters.add(NovelChapterMeta().also { c ->
                        c.name = o.get("name")?.asString?.trim().orEmpty()
                        c.start = o.get("start_lines")?.asInt ?: 0
                        c.end = o.get("end_lines")?.asInt ?: 0
                        c.type = o.get("content_type")?.asInt ?: 1
                        c.imageUrl = o.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    })
                }
            }
        }.getOrNull()
    }
}
