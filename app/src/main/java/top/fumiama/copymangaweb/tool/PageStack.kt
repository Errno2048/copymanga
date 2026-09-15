package top.fumiama.copymangaweb.tool

/**
 * 轻量级浏览页面栈。
 *
 * 只记录「路由 + 滚动位置 + 所属实例」，条目就是字符串和整数，成本可忽略；
 * 真正重的 WebView 由实例 LRU 上限约束（见 MAX_INSTANCES）。
 *
 * 语义：
 * - [report] 每次路由变化/滚动上报时调用；同一页面重复上报只更新位置与时间；
 *   目标已在栈中则把它移到栈顶（去重 + move-to-top，避免「精選↔個人」来回堆叠）。
 * - [popBack] 返回键弹栈；栈里没有上一页时返回 null（调用方不跳转，交回系统=退出）。
 * - 实例被淘汰或结束时 [unregister] 会一并清掉它的条目，保证返回不会指向已销毁的页面。
 */
object PageStack {
    data class Entry(val key: String, val route: String, var scrollY: Int, var at: Long)

    /** 栈长度上限：条目很轻，但也不要无限增长 */
    private const val MAX_ENTRIES = 20

    /** 同时存活的页面实例上限（首页实例固定保留，不计入淘汰） */
    const val MAX_INSTANCES = 4

    private val entries = ArrayList<Entry>()
    private val instances = LinkedHashMap<String, Long>()

    @Synchronized
    fun register(key: String, pinned: Boolean) {
        instances[key] = if (pinned) Long.MAX_VALUE else System.currentTimeMillis()
    }

    @Synchronized
    fun touch(key: String) {
        if (instances[key] != Long.MAX_VALUE) instances[key] = System.currentTimeMillis()
    }

    @Synchronized
    fun unregister(key: String) {
        instances.remove(key)
        entries.removeAll { it.key == key }
    }

    fun isAlive(key: String): Boolean = synchronized(this) { instances.containsKey(key) }

    /** 超过上限时给出应淘汰的实例 key（首页实例 pinned，永不淘汰） */
    @Synchronized
    fun lruVictim(): String? =
        if (instances.size > MAX_INSTANCES) instances.minByOrNull { it.value }?.key else null

    /** 上报当前所在页面 */
    @Synchronized
    fun report(key: String, route: String, scrollY: Int) {
        val top = entries.lastOrNull()
        if (top != null && top.key == key && top.route == route) {
            top.scrollY = scrollY
            top.at = System.currentTimeMillis()
            return
        }
        val idx = entries.indexOfFirst { it.route == route }
        if (idx >= 0) {
            val e = entries.removeAt(idx)
            entries.add(Entry(e.key, e.route, scrollY, System.currentTimeMillis()))
            return
        }
        entries.add(Entry(key, route, scrollY, System.currentTimeMillis()))
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** 返回键：弹栈并返回要回到的页面；没有上一页返回 null */
    @Synchronized
    fun popBack(): Entry? {
        if (entries.size <= 1) return null
        entries.removeAt(entries.size - 1)
        return entries.lastOrNull()
    }

    /** 仅供调试/验证：当前栈内容 */
    @Synchronized
    fun snapshot(): String = entries.joinToString(" | ") { "${it.route}@${it.scrollY}[${it.key}]" }
}
