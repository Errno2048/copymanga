package top.fumiama.copymangaweb.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.databinding.ActivityDlistBinding
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.tool.NovelStore
import java.io.File
import java.util.concurrent.Executors
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

class DlListActivity : Activity() {
    private lateinit var mBinding: ActivityDlistBinding
    private var nullZipDirStr = emptyArray<String>()
    private val fileExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityDlistBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        InsetsTools.applySafeContentInsets(this, mBinding.root)
        mBinding.myt.ttitle.text = intent.getStringExtra("title")
        applyNight()
        loadDirectory(currentDir)
    }

    private val night get() = NightTint.on(this)
    private val barOrigin by lazy { NightTint.captureBars(this) }

    override fun onResume() {
        super.onResume()
        NightTint.applyBars(this, barOrigin)
    }

    private fun applyNight() {
        if (!night) return
        mBinding.root.setBackgroundColor(NightTint.BG)
        mBinding.mylv.setBackgroundColor(NightTint.BG)
        mBinding.myt.titlecard.setCardBackgroundColor(NightTint.SURFACE)
        mBinding.myt.ttitle.setTextColor(NightTint.FG)
        NightTint.applyBars(this, barOrigin)
    }

    /** 系统列表项在暗色下文字仍是深色，这里换成自绘的浅色文字项。 */
    private inner class NightListAdapter(entries: List<String>) :
        ArrayAdapter<String>(this@DlListActivity, android.R.layout.simple_list_item_1, entries) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            (v as? TextView)?.setTextColor(NightTint.FG)
            return v
        }
    }

    override fun onDestroy() {
        fileExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadDirectory(directory: File?) {
        fileExecutor.execute {
            val isRoot = directory == getExternalFilesDir("")
            val jsonFile = File(directory, "info.bin")
            val entries = if (isRoot || !jsonFile.exists()) {
                directory?.list()?.sortedWith(::compareFileNames)
            } else null
            runOnUiThread {
                if (!isFinishing && !isDestroyed && entries != null) {
                    showDirectory(directory, entries)
                }
            }
        }
    }

    private fun showDirectory(directory: File?, entries: List<String>) {
        mBinding.mylv.adapter = if (night) NightListAdapter(entries)
            else ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        mBinding.mylv.setOnItemClickListener { _, _, position, _ ->
            val chosenFile = File(directory, entries[position])
            val chosenJson = File(chosenFile, "info.bin")
            when {
                chosenJson.exists() -> callDownloadActivity(chosenJson)
                File(chosenFile, NovelStore.META_FILE).exists() -> {
                    startActivity(
                        Intent(this, ViewNovelActivity::class.java)
                            .putExtra(ViewNovelActivity.EXTRA_BOOK, chosenFile.name)
                    )
                }
                chosenFile.isDirectory -> {
                    currentDir = chosenFile
                    startActivity(
                        Intent(this, DlListActivity::class.java)
                            .putExtra("title", entries[position])
                    )
                }
                chosenFile.name.endsWith(".zip", ignoreCase = true) -> {
                    Toast.makeText(this, "加载中...", Toast.LENGTH_SHORT).show()
                    val zipFiles = entries
                        .map { File(directory, it) }
                        .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
                    ViewMangaActivity.zipFile = chosenFile
                    ViewMangaActivity.titleText = entries[position]
                    ViewMangaActivity.zipPosition = zipFiles.indexOf(chosenFile)
                    ViewMangaActivity.zipList = zipFiles.toTypedArray()
                    ViewMangaActivity.cd = directory
                    ViewMangaActivity.nextChapterUrl = null
                    ViewMangaActivity.previousChapterUrl = null
                    startActivity(Intent(this, ViewMangaActivity::class.java))
                }
            }
        }
        mBinding.mylv.setOnItemLongClickListener { _, _, position, _ ->
            val chosenFile = File(directory, entries[position])
            AlertDialog.Builder(this)
                .setIcon(R.drawable.ic_launcher_foreground)
                .setMessage("在此执行删除/查错?")
                .setTitle("提示")
                .setPositiveButton("删除") { _, _ ->
                    fileExecutor.execute {
                        if (chosenFile.exists()) deleteRecursively(chosenFile)
                        loadDirectory(directory)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("查错") { _, _ -> checkDirectory(chosenFile) }
                .show()
            true
        }
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.let {
            for (i in it)
                if (i.isDirectory) deleteRecursively(i)
                else i.delete()
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

    private fun callDownloadActivity(jsonFile: File){
        DlActivity.json = jsonFile.readText()
        DlActivity.comicName = jsonFile.parentFile?.name?:"Null"
        startActivity(
            Intent(this, DlActivity::class.java)
                .putExtra("callFromDlList", true)
        )
    }

    private fun findInvalidZipFiles(file: File): List<String> {
        val invalidFiles = mutableListOf<String>()
        if (file.isDirectory) file.listFiles()?.forEach { child ->
            if (child.isDirectory) invalidFiles += findInvalidZipFiles(child)
            else if (child.extension.equals("zip", ignoreCase = true) && !checkZip(child)) {
                invalidFiles += child.path.substringAfterLast(getExternalFilesDir("").toString())
            }
        }
        return invalidFiles
    }

    private fun checkZip(f: File): Boolean {
        return try {
            val exist = f.exists()
            if (!exist) true
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

    private fun compareFileNames(first: String, second: String): Int {
        return if (first.endsWith(".zip") && second.endsWith(".zip")) {
            (10000 * getFloat(first) - 10000 * getFloat(second) + 0.5).toInt()
        } else first.compareTo(second)
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
                val file = File(exf, path)
                if (file.exists()) file.delete()
            }
            loadDirectory(currentDir)
        }
    }

    private fun getFloat(oldString: String): Float {
        val newString = StringBuffer()
        var matcher = Pattern.compile("\\d+.+\\d+").matcher(oldString)
        while (matcher.find()) newString.append(matcher.group())
        if (newString.isEmpty()) {
            matcher = Pattern.compile("\\d").matcher(oldString)
            while (matcher.find()) newString.append(matcher.group())
        }
        return newString.toString().toFloatOrNull() ?: 0f
    }

    companion object {
        var currentDir: File? = null
    }
}

