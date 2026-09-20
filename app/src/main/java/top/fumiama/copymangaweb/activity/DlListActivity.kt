package top.fumiama.copymangaweb.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.databinding.ActivityDlistBinding
import top.fumiama.copymangaweb.tool.ComicMetaStore
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.tool.NovelStore
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * 我的下载：分「漫畫 / 小說」两个子页，各自按书架样式（封面 + 标题 + 副标题）网格展示。
 *
 * 数据全部来自本地目录：
 * - 漫画：`<漫画目录>/info.bin`（章节表，点进去是下载页）或目录里有 zip（直接进阅读器），
 *   封面/作者取 `meta.json` 与 `cover.jpg`（下载章节时顺手保存下来的）；
 * - 小说：`<书名>/novel.json`（元数据，含封面/作者），封面取 `<书名>/cover.jpg`。
 *
 * 封面缺失时退回元信息里的远程地址，再退回占位图标。
 */
class DlListActivity : Activity() {
    private lateinit var mBinding: ActivityDlistBinding
    private var nullZipDirStr = emptyArray<String>()
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val adapter = DlAdapter()
    private var tab = TYPE_COMIC
    private var entries: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityDlistBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        InsetsTools.applySafeContentInsets(this, mBinding.root)
        mBinding.myt.ttitle.text = intent.getStringExtra("title") ?: "我的下载"
        mBinding.mygrid.layoutManager = GridLayoutManager(this, COLUMNS)
        mBinding.mygrid.adapter = adapter
        mBinding.dlcomic.setOnClickListener { selectTab(TYPE_COMIC) }
        mBinding.dlnovel.setOnClickListener { selectTab(TYPE_NOVEL) }
        applyNight()
        selectTab(TYPE_COMIC)
    }

    override fun onResume() {
        super.onResume()
        NightTint.applyBars(this, barOrigin)
        reload()
    }

    private val night get() = NightTint.on(this)
    private val barOrigin by lazy { NightTint.captureBars(this) }

    private fun applyNight() {
        if (!night) return
        mBinding.root.setBackgroundColor(NightTint.BG)
        mBinding.mygrid.setBackgroundColor(NightTint.BG)
        mBinding.myt.titlecard.setCardBackgroundColor(NightTint.SURFACE)
        mBinding.myt.ttitle.setTextColor(NightTint.FG)
        NightTint.applyBars(this, barOrigin)
    }

    private fun selectTab(which: Int) {
        tab = which
        val selected = if (night) R.drawable.rndbg_selected_dark else R.drawable.rndbg
        val normal = if (night) R.drawable.rndbg_white_dark else R.drawable.rndbg_white
        for ((btn, isMine) in listOf(
            mBinding.dlcomic to (which == TYPE_COMIC),
            mBinding.dlnovel to (which == TYPE_NOVEL)
        )) {
            btn.setBackgroundResource(if (isMine) selected else normal)
            btn.setTextColor(if (night) NightTint.FG else 0xFF333333.toInt())
        }
        reload()
    }

    private fun reload() {
        fileExecutor.execute {
            val list = runCatching { scan(tab) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                entries = list
                adapter.notifyDataSetChanged()
            }
        }
    }

    // ---------------- 扫描本地目录 ----------------

    private class Entry(
        val kind: Int,
        val dir: File,
        val title: String,
        val subtitle: String,
        val cover: File?,
        val coverUrl: String,
        /** 漫画：章节 zip 数；小说：卷数 */
        val count: Int
    )

    private fun scan(kind: Int): List<Entry> {
        val root = getExternalFilesDir("") ?: return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir -> entryOf(dir, kind) }
            .sortedBy { it.title }
    }

    private fun entryOf(dir: File, kind: Int): Entry? {
        val novelMeta = File(dir, NovelStore.META_FILE)
        if (novelMeta.exists()) {
            if (kind != TYPE_NOVEL) return null
            val m = NovelStore.load(this, dir.name)
            val author = m?.author.orEmpty()
            val cover = NovelStore.coverFile(this, dir.name)
            return Entry(
                TYPE_NOVEL, dir,
                m?.name?.ifBlank { dir.name } ?: dir.name,
                author.ifBlank { "${m?.volumes?.size ?: 0} 卷" },
                cover.takeIf { it.exists() && it.length() > 0 }, m?.cover.orEmpty(),
                m?.volumes?.size ?: 0
            )
        }
        if (kind != TYPE_COMIC) return null
        val zipCount = countZips(dir)
        val meta = ComicMetaStore.readFromDir(dir)
        if (zipCount == 0 && meta == null) return null
        val cover = ComicMetaStore.coverFile(dir)
        val subtitle = meta?.author.orEmpty().ifBlank { "$zipCount 章" }
        return Entry(
            TYPE_COMIC, dir,
            meta?.name?.ifBlank { null } ?: dir.name,
            subtitle,
            cover.takeIf { it.exists() && it.length() > 0 }, meta?.cover.orEmpty(),
            zipCount
        )
    }

    /** 章节 zip：漫画目录结构是 <漫画>/<话>/<话>.zip，最多看两层 */
    private fun countZips(dir: File, depth: Int = 0): Int {
        val files = dir.listFiles() ?: return 0
        var n = files.count { it.isFile && it.extension.equals("zip", true) }
        if (depth < 2) n += files.filter { it.isDirectory }.sumOf { countZips(it, depth + 1) }
        return n
    }

    private fun zipsOf(dir: File, depth: Int = 0): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        val here = files.filter { it.isFile && it.extension.equals("zip", true) }
        if (depth >= 2) return here
        return here + files.filter { it.isDirectory }.flatMap { zipsOf(it, depth + 1) }
    }

    // ---------------- 网格 ----------------

    private inner class DlAdapter : RecyclerView.Adapter<DlAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.decover)
            val title: TextView = v.findViewById(R.id.detitle)
            val meta: TextView = v.findViewById(R.id.demeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.line_dlentry, parent, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = entries.getOrNull(position) ?: return
            holder.title.text = e.title
            holder.meta.text = e.subtitle
            holder.title.setTextColor(if (night) NightTint.FG else 0xFF333333.toInt())
            holder.meta.setTextColor(if (night) 0xFF9A9A9A.toInt() else 0xFF888888.toInt())
            holder.cover.setImageResource(R.drawable.ic_dl)
            val src: Any? = e.cover ?: e.coverUrl.takeIf { it.isNotBlank() }
            if (src != null) {
                Glide.with(this@DlListActivity).load(src).centerCrop().into(holder.cover)
            }
            holder.itemView.setOnClickListener { open(e) }
            holder.itemView.setOnLongClickListener { confirmDelete(e); true }
        }
    }

    // ---------------- 打开 / 删除 ----------------

    private fun open(e: Entry) {
        if (e.kind == TYPE_NOVEL) {
            startActivity(
                Intent(this, ViewNovelActivity::class.java)
                    .putExtra(ViewNovelActivity.EXTRA_BOOK, e.dir.name)
            )
            return
        }
        val info = File(e.dir, "info.bin")
        if (info.exists()) {
            callDownloadActivity(info)
            return
        }
        val zips = zipsOf(e.dir)
        val first = zips.firstOrNull()
        if (first == null) {
            Toast.makeText(this, "没有找到已下载的章节", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "加载中...", Toast.LENGTH_SHORT).show()
        ViewMangaActivity.zipFile = first
        ViewMangaActivity.titleText = first.name
        ViewMangaActivity.zipPosition = zips.indexOf(first)
        ViewMangaActivity.zipList = zips.toTypedArray()
        ViewMangaActivity.cd = first.parentFile
        ViewMangaActivity.nextChapterUrl = null
        ViewMangaActivity.previousChapterUrl = null
        startActivity(Intent(this, ViewMangaActivity::class.java))
    }

    private fun callDownloadActivity(jsonFile: File) {
        DlActivity.json = jsonFile.readText()
        DlActivity.comicName = jsonFile.parentFile?.name ?: "Null"
        DlActivity.comicMetaJson = ""
        startActivity(
            Intent(this, DlActivity::class.java)
                .putExtra("callFromDlList", true)
        )
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setIcon(R.drawable.ic_launcher_foreground)
            .setTitle(e.title)
            .setMessage("在此执行删除/查错?")
            .setPositiveButton("删除") { _, _ ->
                fileExecutor.execute {
                    if (e.dir.exists()) deleteRecursively(e.dir)
                    runOnUiThread { reload() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("查错") { _, _ -> checkDirectory(e.dir) }
            .show()
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteRecursively(child) else child.delete()
        }
        f.delete()
    }

    private fun checkDirectory(directory: File) {
        fileExecutor.execute {
            val invalidFiles = findInvalidZipFiles(directory)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                nullZipDirStr = invalidFiles.toTypedArray()
                if (invalidFiles.isNotEmpty()) showErrorZip(invalidFiles.joinToString("\n"))
                else Toast.makeText(this, "未发现错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findInvalidZipFiles(file: File): List<String> {
        val invalidFiles = mutableListOf<String>()
        if (file.isDirectory) file.listFiles()?.forEach { child ->
            if (child.isDirectory) invalidFiles += findInvalidZipFiles(child)
            else if (child.extension.equals("zip", true) && !checkZip(child)) {
                invalidFiles += child.path.substringAfterLast(getExternalFilesDir("").toString())
            }
        }
        return invalidFiles
    }

    private fun checkZip(f: File): Boolean {
        return try {
            if (!f.exists()) true
            else {
                var re = true
                ZipInputStream(f.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && zip.read() == -1 && entry.size == 0L) {
                            re = false
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                re
            }
        } catch (e: Exception) {
            Log.e("DlListActivity", "读取 ${f.name} 失败", e)
            false
        }
    }

    private fun showErrorZip(msg: CharSequence) = AlertDialog.Builder(this)
        .setIcon(R.drawable.ic_launcher_foreground)
        .setTitle("找到以下错误文件,是否删除?")
        .setMessage(msg)
        .setPositiveButton(android.R.string.ok) { _, _ -> deleteErrorZip() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()

    private fun deleteErrorZip() {
        val exf = getExternalFilesDir("")
        fileExecutor.execute {
            for (path in nullZipDirStr) {
                val f = File(exf, path)
                if (f.exists()) f.delete()
            }
            runOnUiThread { reload() }
        }
    }

    override fun onDestroy() {
        fileExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val TYPE_COMIC = 0
        const val TYPE_NOVEL = 1
        private const val COLUMNS = 3

        /** 其它页面只用到这个静态字段（进入下载页时的当前目录） */
        var currentDir: File? = null
    }
}
