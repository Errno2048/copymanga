package top.fumiama.copymangaweb.activity

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import com.google.gson.Gson
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.activity.MainActivity.Companion.wm
import top.fumiama.copymangaweb.activity.template.ToolsBoxActivity
import top.fumiama.copymangaweb.data.ComicStructure
import top.fumiama.copymangaweb.databinding.ActivityDlBinding
import top.fumiama.copymangaweb.handler.DlHandler
import top.fumiama.copymangaweb.tool.ComicMeta
import top.fumiama.copymangaweb.tool.ComicMetaStore
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.MangaDlTools
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.tool.MangaDlTools.Companion.wmdlt
import top.fumiama.copymangaweb.view.ChapterToggleButton
import top.fumiama.copymangaweb.view.LazyScrollView
import top.fumiama.copymangaweb.web.JSHidden
import top.fumiama.copymangaweb.web.JS
import top.fumiama.copymangaweb.web.WebChromeClient
import java.io.File
import java.lang.Thread.sleep

class DlActivity : ToolsBoxActivity() {
    lateinit var mBinding: ActivityDlBinding
    private var tbtncnt = 0
    private var isNewTitle = false
    var haveSElectAll = false
    var checkedChapter = 0
    var dldChapter = 0
    var haveDlStarted = false
    private var btnNumPerRow = 4
    private lateinit var toggleButtonLine: View
    var tbtnlist: Array<ChapterToggleButton> = arrayOf()
    private val handler = DlHandler(this, Looper.myLooper()!!)
    private var btnw = 0
    private var cdwnWidth = 0
    private var canDl = false
    private lateinit var mangaDlTools: MangaDlTools
    var multiSelect = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityDlBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        InsetsTools.applySafeContentInsets(this, mBinding.root)
        wm?.get()?.saveUrlsOnly = true
        mangaDlTools = MangaDlTools(this)
        mBinding.dwh.apply { post {
            settings.userAgentString = getString(R.string.pc_ua)
            webChromeClient = WebChromeClient()
            setWebViewClient("h.js")
            loadJSInterface(JSHidden(onLoadChapter = { onHiddenChapterLoaded(it) }))
        } }
        handler.sendEmptyMessage(DlHandler.INITIALIZE_LAYOUTS)
        applyNight()
    }

    override fun onResume() {
        super.onResume()
        NightTint.applyBars(this, barOrigin)
    }

    private val night get() = NightTint.on(this)
    private val barOrigin by lazy { NightTint.captureBars(this) }
    private val toggleBgRes get() = if (night) R.drawable.toggle_button_dark else R.drawable.toggle_button
    private val downloadedBgRes get() = if (night) R.drawable.rndbg_downloaded_dark else R.drawable.rndbg_checked

    /** 下载页是原生界面，这里跟随网页端的夜间偏好着色。 */
    private fun applyNight() {
        if (!night) return
        mBinding.root.setBackgroundColor(NightTint.BG)
        mBinding.dllazys.setBackgroundColor(NightTint.BG)
        mBinding.ldwn.setBackgroundColor(NightTint.BG)
        mBinding.ldwndiv.setBackgroundColor(NightTint.DIVIDER)
        mBinding.dtitle.titlecard.setCardBackgroundColor(NightTint.SURFACE)
        mBinding.dtitle.ttitle.setTextColor(NightTint.FG)
        // 下载条用的是很浅的 colorBlue，夜间改成暗色并提高不透明度，避免露出下面的内容
        mBinding.dldlbar.csdwn.alpha = 1f
        mBinding.dldlbar.cdwn.setCardBackgroundColor(NightTint.SURFACE)
        mBinding.dldlbar.tdwn.setTextColor(NightTint.FG)
        mBinding.dldlbar.textView.setTextColor(NightTint.FG)
        NightTint.applyBars(this, barOrigin)
    }

    override fun onDestroy() {
        wm?.get()?.saveUrlsOnly = false
        wmdlt?.get()?.exit = true
        handler.removeCallbacksAndMessages(null)
        mBinding.dwh.destroy()
        super.onDestroy()
    }

    private fun showDlCard() {
        ObjectAnimator.ofFloat(
            mBinding.dldlbar.csdwn,
            "translationX",
            cdwnWidth.toFloat() * 0.9f,
            0f
        ).setDuration(
            233
        ).start()
    }

    private fun hideDlCard() {
        ObjectAnimator.ofFloat(
            mBinding.dldlbar.csdwn,
            "translationX",
            0f,
            cdwnWidth.toFloat() * 0.9f
        ).setDuration(
            233
        ).start()
    }

    private fun fillChapters(): Boolean {
        mangaDlTools.allocateChapterUrls(checkedChapter)
        var ok = true
        for (i in tbtnlist) {
            if (i.isChecked && !mangaDlTools.dlChapterUrl(i.url.toString())) {
                ok = false
                handler.obtainMessage(DlHandler.CHAPTER_DOWNLOAD_FAILED, i.index, 0).sendToTarget()
            }
        }
        return ok && mangaDlTools.waitChapterUrlsReady()
    }

    private fun dlThread(dlMethod: (i: ChapterToggleButton) -> Unit) {
        sleep(10000)
        for (i in tbtnlist) {
            if (i.isChecked) dlMethod(i)
            if (!canDl) {
                checkedChapter -= dldChapter
                dldChapter = 0
                break
            }
        }
        if (canDl) {
            haveDlStarted = false
            canDl = false
        }
        handler.sendEmptyMessage(DlHandler.SET_DOWNLOAD_CARD_BLUE)
    }

    @SuppressLint("SetTextI18n")
    fun setLayouts() {
        mBinding.dtitle.ttitle.apply { post { text = comicName } }
        val widthData = toolsBox.calcWidthFromDp(8, 64)
        btnNumPerRow = widthData[0]
        btnw = widthData[1]
        mBinding.dldlbar.csdwn.apply { post { viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                cdwnWidth = width
                viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        }) } }
        mBinding.dllazys.onScrollListener = object : LazyScrollView.OnScrollListener {
            override fun onBottom() {}
            override fun onScroll() { if (mBinding.dldlbar.csdwn.translationX == 0f) hideDlCard() }
            override fun onTop() {}
        }
        mBinding.dldlbar.cdwn.let { it.post {
            it.setOnClickListener {
                if (mBinding.dldlbar.csdwn.translationX != 0f) showDlCard()
                else if (checkedChapter == 0) hideDlCard()
                else {
                    mBinding.dldlbar.pdwn.progress = 0
                    if (canDl || checkedChapter == 0) canDl = false
                    else {
                        haveDlStarted = true
                        canDl = true
                        handler.sendEmptyMessage(DlHandler.SET_DOWNLOAD_CARD_RED)
                        Toast.makeText(this@DlActivity, "请耐心等待加载...", Toast.LENGTH_SHORT).show()
                        Thread {
                            if (fillChapters()) {
                                dlThread { downloadChapterPages(it) }
                            } else {
                                canDl = false
                                haveDlStarted = false
                                handler.sendEmptyMessage(DlHandler.SET_DOWNLOAD_CARD_BLUE)
                            }
                        }.start()
                    }
                }
            }
            it.setOnLongClickListener {
                handler.sendEmptyMessage(DlHandler.TOGGLE_SELECT_ALL)
                return@setOnLongClickListener true
            }
        } }
        mBinding.dtitle.isearch.apply { post {
            setOnClickListener { showMultiSelectInfo() }
        } }
        Thread{ analyzeStructure() }.start()
    }

    private fun showMultiSelectInfo() {
        toolsBox.buildInfo("进入多选模式？", "确定后，长按下载条可选中全部漫画，而不仅限于未下载者；点击已下载漫画可进行选择。",
            "确定", null, "取消", { multiSelect = true })
    }

    private fun analyzeStructure() {
        ViewMangaActivity.zipList = emptyArray()
        Gson().fromJson(json?.reader(), Array<ComicStructure>::class.java)?.let {
            for (group in it) {
                val tc = layoutInflater.inflate(R.layout.line_caption, mBinding.ldwn, false)
                tc.findViewById<TextView>(R.id.tcptn).apply {
                    text = group.name
                    if (night) setTextColor(NightTint.FG)
                }
                mBinding.ldwn.apply { post {
                    addView(
                        tc,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        layoutInflater.inflate(R.layout.div_h, mBinding.ldwn, false).apply {
                            if (night) setBackgroundColor(NightTint.DIVIDER)
                        },
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                } }
                isNewTitle = true
                for (chapter in group.chapters) addToggleButton(chapter.name, chapter.url, group.name)
            }
        }
        val mangaHome = File("${getExternalFilesDir("")}/$comicName")
        val jsonFile = File(mangaHome, "info.bin")
        if(!mangaHome.exists()) mangaHome.mkdirs()
        if(!(jsonFile.exists() && intent.getBooleanExtra("callFromDlList", false))) json?.let { jsonFile.writeText(it) }
        // 元信息 + 封面：下载任意章节时顺手保存一次（已有则不重下）
        val meta = runCatching {
            comicMetaJson.takeIf { it.isNotBlank() }?.let {
                Gson().fromJson(it, ComicMeta::class.java)
            }
        }.getOrNull() ?: ComicMetaStore.readFromDir(mangaHome)
        if (meta != null) {
            if (meta.name.isBlank()) meta.name = comicName
            ComicMetaStore.writeInto(mangaHome, meta)
            ComicMetaStore.ensureCover(mangaHome, meta)
        }
    }

    private fun onHiddenChapterLoaded(content: String) {
        val listChapter = content.split('\n')
        val hash = listChapter.getOrNull(0)?.substringAfterLast(' ')
        if (hash.isNullOrBlank()) return
        val imgs = listChapter.drop(3).filter { it.isNotBlank() }.toTypedArray()
        mangaDlTools.setChapterImages(hash, imgs)
    }

    private fun downloadChapterPages(i: ChapterToggleButton) {
        mangaDlTools.onDownloadedListener =
            object : MangaDlTools.OnDownloadedListener {
                override fun handleMessage(succeed: Boolean) {
                    handler.obtainMessage(
                        if (succeed) DlHandler.CHAPTER_DOWNLOAD_SUCCEEDED
                        else DlHandler.CHAPTER_DOWNLOAD_FAILED,
                        i.index,
                        0
                    )
                        .sendToTarget()
                }
                override fun handleMessage(succeed: Boolean, pageNow: Int) {
                    handler.obtainMessage(
                        DlHandler.PAGE_DOWNLOAD_FINISHED,
                        i.index,
                        pageNow,
                        succeed
                    ).sendToTarget()
                }
                override fun handleMessage(pageNow: Int){
                    handler.obtainMessage(
                        DlHandler.PAGE_DOWNLOAD_RETRYING,
                        i.index,
                        pageNow
                    ).sendToTarget()
                }
            }
        i.hash?.let {
            mangaDlTools.dlChapterAndPackIntoZip(
                File("${getExternalFilesDir("")}/$comicName/${i.hint}/${i.textOn}.zip"),
                it
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun addToggleButton(title: String, url: String, caption: String) {
        if ((tbtncnt % btnNumPerRow == 0) || isNewTitle) {
            toggleButtonLine = layoutInflater.inflate(R.layout.line_horizonal, mBinding.ldwn, false)
            mBinding.ldwn.apply {
                val t = toggleButtonLine
                post { addView(t) }
            }
            tbtncnt = 0
            isNewTitle = false
        }
        val tbv = layoutInflater.inflate(R.layout.button_tbutton, toggleButtonLine.findViewById(R.id.ltbtn), false)
        val tbvTbtn = tbv.findViewById<ChapterToggleButton>(R.id.tbtn)?:return
        tbvTbtn.index = tbtnlist.size
        tbtnlist += tbvTbtn
        tbvTbtn.url = url
        tbtncnt++
        tbvTbtn.textOff = title
        tbvTbtn.textOn = title
        tbvTbtn.text = title
        tbvTbtn.hint = caption
        tbvTbtn.layoutParams.width = btnw
        val zipFile = File("${getExternalFilesDir("")}/$comicName/$caption/$title.zip")
        if (night) {
            tbvTbtn.setTextColor(NightTint.FG)
            if (!zipFile.exists()) tbvTbtn.setBackgroundResource(R.drawable.toggle_button_dark)
        }
        val zipPosition = if (zipFile.exists()) {
            ViewMangaActivity.zipList.orEmpty().size.also {
                ViewMangaActivity.zipList = (
                    ViewMangaActivity.zipList?.toList().orEmpty() + zipFile
                ).toTypedArray()
            }
        } else null
        if (zipFile.exists()) {
            tbvTbtn.setBackgroundResource(downloadedBgRes)
            tbvTbtn.isChecked = false
            tbvTbtn.freezesText = true
        }
        toggleButtonLine.apply { post {
            findViewById<LinearLayout>(R.id.ltbtn)?.addView(tbv)
            invalidate()
        } }
        tbvTbtn.setOnClickListener { v ->
            val normalAct = (multiSelect && zipFile.exists()) || !zipFile.exists()
            val tbtn = v.findViewById<ChapterToggleButton>(R.id.tbtn)?:return@setOnClickListener
            if (zipFile.exists() && !tbtn.isChecked) tbtn.apply { post { setBackgroundResource(downloadedBgRes) } }
            else if(normalAct) tbtn.apply { post { setBackgroundResource(toggleBgRes) } }
            if (normalAct) {
                mBinding.dldlbar.tdwn.apply {
                    if (tbtn.isChecked) post {
                        text = "$dldChapter/${++checkedChapter}"
                    } else post {
                        text = "$dldChapter/${--checkedChapter}"
                    }
                }
            } else if(tbtn.isChecked) {
                tbtn.apply { post {
                    isChecked = false
                    zipPosition?.let { callVM(title, zipFile, it) }
                } }
            }
        }
        tbvTbtn.setOnLongClickListener {
            if (zipFile.exists()) {
                toolsBox.buildInfo("确认删除这些章节?",
                    "该操作将不可撤销",
                    "确定",
                    null,
                    "取消",
                    {
                        if (checkedChapter == 0) {
                            tbvTbtn.apply { post { isChecked = true } }
                            mBinding.dldlbar.tdwn.apply { post {
                                text = "$dldChapter/${++checkedChapter}"
                            } }
                        }
                        handler.sendEmptyMessage(DlHandler.DELETE_SELECTED_CHAPTERS)
                    })
            }
            true
        }
    }

    fun deleteChapters() {
        for (i in tbtnlist) {
            if (i.isChecked) {
                val f = File("${getExternalFilesDir("")}/$comicName/${i.hint}/${i.textOn}.zip")
                if (f.exists()) {
                    deleteChapter(f, i)
                    checkedChapter--
                }
            }
        }
        handler.sendEmptyMessage(DlHandler.UPDATE_CHAPTER_PROGRESS)
    }

    private fun callVM(titleText: String, zipFile: File, zipPosition:Int) {
        ViewMangaActivity.titleText = titleText
        ViewMangaActivity.zipFile = zipFile
        ViewMangaActivity.zipPosition = zipPosition
        ViewMangaActivity.cd = zipFile.parentFile
        ViewMangaActivity.nextChapterUrl = null
        ViewMangaActivity.previousChapterUrl = null
        startActivity(Intent(this@DlActivity, ViewMangaActivity::class.java))
    }

    private fun deleteChapter(f: File, v: ToggleButton) {
        f.delete()
        v.apply { post{
            setBackgroundResource(toggleBgRes)
            isChecked = false
        } }
    }

    @SuppressLint("SetTextI18n")
    fun updateProgressBar() {
        mBinding.dldlbar.tdwn.apply { post {
            text = "${++dldChapter}/$checkedChapter"
        } }
        setProgress2(dldChapter * 100 / checkedChapter, 233)
    }

    fun updateProgressBar(pageNow: Int, size: Int) {
        val delta = 100 / checkedChapter
        val start = dldChapter * delta
        val now = pageNow * delta / size
        setProgress2(start + now, 64)
    }

    fun setProgress2(end: Int, duration: Long) {
        val pdwn = mBinding.dldlbar.pdwn
        ObjectAnimator.ofInt(
            pdwn,
            "progress",
            pdwn.progress,
            end
        ).setDuration(duration).start()
    }

    companion object {
        var comicName = "Null"
        var json: String? = null
        /** 详情页抓到的元信息（JSON，由 MainActivity 按 pathWord 取出） */
        var comicMetaJson: String = ""
    }
}