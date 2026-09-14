package top.fumiama.copymangaweb.tool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 小说整本下载：逐卷取详情并下载正文 txt（原样编码字节），
 * 完成后本地即可离线阅读；元数据写入 novel.json，因此下载列表里也能进入阅读器。
 */
object NovelDownloader {
    @Volatile private var running = false

    fun isRunning(): Boolean = running

    fun start(ctx: Context, req: NovelOpenRequest) {
        if (running) {
            toast(ctx, "已有小说正在下载，请稍候")
            return
        }
        running = true
        toast(ctx, "开始下载小说：${req.name}")
        Thread {
            var ok = 0
            var total = 0
            try {
                val meta = NovelStore.merge(ctx, req)
                val apiBase = meta.apiBase.ifBlank { req.apiBase }
                if (meta.volumes.isEmpty()) {
                    meta.volumes = NovelApi.volumes(apiBase, meta.pathWord).toMutableList()
                }
                total = meta.volumes.size
                if (total == 0) {
                    toast(ctx, "无法获取卷列表，请稍后重试")
                    return@Thread
                }
                meta.volumes.forEachIndexed { i, info ->
                    val vol = meta.details[info.id]
                        ?: NovelApi.volumeDetail(apiBase, meta.pathWord, info.id)
                            ?.also { meta.details[info.id] = it }
                    if (vol != null && vol.txtAddr.isNotBlank()) {
                        val dest = NovelStore.txtFile(ctx, meta.name, vol.name)
                        if (dest.exists() || NovelApi.downloadBinary(vol.txtAddr, dest)) ok++
                    }
                    NovelStore.save(ctx, meta)
                    toast(ctx, "小说下载中 ${i + 1}/$total")
                }
            } catch (e: Exception) {
                toast(ctx, "小说下载出错：${e.message}")
            } finally {
                running = false
            }
            toast(ctx, "小说下载完成：$ok/$total 卷")
        }.start()
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
