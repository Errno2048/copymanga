package top.fumiama.copymangaweb.web

import android.content.Context
import android.util.Log
import top.fumiama.copymangaweb.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拷贝漫画的镜像线路会轮换，任何单一域名都可能失效。
 * 维护线路列表；加载前先做短超时探测选出可用线路，
 * 避免把整个加载流程交给 WebView 去等一条死线路的网络超时（实测可达约 130 秒）。
 */
object Mirrors {
    private const val PREF = "mirrors"
    private const val KEY_WORKING = "working_index"
    private const val PROBE_TIMEOUT_MS = 3500

    @Volatile private var list: Array<String> = emptyArray()
    @Volatile private var index = 0

    fun init(context: Context) {
        if (list.isNotEmpty()) return
        list = context.resources.getStringArray(R.array.web_mirrors)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toTypedArray()
        val saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_WORKING, 0)
        index = saved.coerceIn(0, maxOf(0, list.size - 1))
        Log.d("MyWC", "mirrors: ${list.joinToString()}, start=$index")
    }

    val all: Array<String> get() = list
    val current: String get() = list.getOrElse(index) { list.firstOrNull() ?: "" }
    val detailPc: String get() = current.takeIf { it.isNotEmpty() }?.let { "$it/comic" } ?: ""

    fun allows(url: String): Boolean = list.any { it.isNotEmpty() && url.startsWith(it) }

    /** 切到下一条线路；已试满一整圈则返回 null，避免无限重试。 */
    fun advance(attempts: Int): String? {
        if (list.isEmpty() || attempts >= list.size) return null
        index = (index + 1) % list.size
        return current
    }

    /** 记录实际加载成功的线路，供下次启动优先使用。 */
    fun markWorking(context: Context, url: String) {
        val i = list.indexOfFirst { it.isNotEmpty() && url.startsWith(it) }
        if (i < 0) return
        index = i
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_WORKING, i).apply()
    }

    /**
     * 逐条探测线路，返回第一条可达的；必须在线程池/IO 线程调用。
     * 先试探上次可用的那条，其余按列表顺序补试。
     */
    fun probe(): String {
        if (list.isEmpty()) return ""
        val order = LinkedHashSet<Int>()
        order.add(index)
        for (i in list.indices) order.add(i)
        for (i in order) {
            if (reachable(list[i])) {
                index = i
                Log.d("MyWC", "probe ok -> ${list[i]}")
                return list[i]
            }
            Log.d("MyWC", "probe fail -> ${list[i]}")
        }
        Log.w("MyWC", "probe: none reachable, use ${current}")
        return current
    }

    private fun reachable(base: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(base).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val code = conn.responseCode
            // 只要 HTTP 层有响应就算线路可达（302/403 等同样说明域名与链路正常）
            code in 200..499
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
