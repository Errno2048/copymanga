package top.fumiama.copymangaweb.handler

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.Toast
import android.widget.ToggleButton
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.activity.DlActivity
import top.fumiama.copymangaweb.tool.MangaDlTools.Companion.wmdlt
import java.lang.ref.WeakReference

class DlHandler(activity: DlActivity, looper: Looper) : Handler(looper) {
    private val da = WeakReference(activity)
    private val d get() = da.get()
    private var size = 0
    private var refreshSize = true

    @SuppressLint("SetTextI18n")
    override fun handleMessage(msg: Message) {
        super.handleMessage(msg)
        val chapterIndex = msg.arg1
        val pageNumber = msg.arg2
        when (msg.what) {
            INITIALIZE_LAYOUTS -> d?.setLayouts()
            CHAPTER_DOWNLOAD_SUCCEEDED -> {
                d?.tbtnlist?.get(chapterIndex)?.apply { post {
                    setBackgroundResource(R.drawable.rndbg_checked)
                    isChecked = false
                    d?.updateProgressBar()
                    if (d?.haveDlStarted == false) {
                        d?.dldChapter = 0
                        d?.checkedChapter = 0
                        postDelayed({
                            d?.setProgress2(0, 233)
                            d?.mBinding?.dldlbar?.tdwn?.apply { post {
                                text = d?.getString(R.string.zero_per_zero)
                            } }
                        }, 400)
                    }
                } }
            }
            CHAPTER_DOWNLOAD_FAILED -> {
                d?.tbtnlist?.get(chapterIndex)?.apply { post {
                    setBackgroundResource(R.drawable.rndbg_error)
                    d!!.dldChapter--
                    Toast.makeText(d, "下载${d?.tbtnlist?.get(chapterIndex)?.textOn}失败", Toast.LENGTH_SHORT).show()
                    d?.updateProgressBar()
                } }
            }
            TOGGLE_SELECT_ALL -> {
                d?.mBinding?.dldlbar?.pdwn?.apply { post { progress = 0 } }
                val selectDownloaded = d?.multiSelect?:false
                if (d?.haveSElectAll == true) {
                    d?.tbtnlist?.forEach { i ->
                        i.apply { post {
                            if(freezesText) setBackgroundResource(R.drawable.rndbg_checked)
                            else setBackgroundResource(R.drawable.toggle_button)
                            isChecked = false
                        } }
                    }
                    d?.haveSElectAll = false
                    d?.checkedChapter = 0
                    d?.dldChapter = 0
                } else {
                    d?.let {
                        val checkBtn = { i: ToggleButton, a: DlActivity ->
                            i.apply { post {
                                setBackgroundResource(R.drawable.toggle_button)
                                isChecked = true
                                a.checkedChapter++
                            } }
                        }
                        for (i in it.tbtnlist) {
                            if(selectDownloaded) checkBtn(i, it)
                            else if(!i.freezesText) checkBtn(i, it)
                        }
                    }
                    d?.haveSElectAll = true
                }
                d?.mBinding?.dldlbar?.tdwn?.apply { post {
                    text = "${d?.dldChapter}/${d?.checkedChapter}"
                } }
            }
            PAGE_DOWNLOAD_FINISHED -> {
                setSize(pageNumber, chapterIndex)
                d?.updateProgressBar(pageNumber, size)
                if (msg.obj != true) {
                    Toast.makeText(d, "下载${d?.tbtnlist?.get(chapterIndex)?.textOn}的第${pageNumber}页失败", Toast.LENGTH_SHORT).show()
                }else{
                    val progressTxt = d?.mBinding?.dldlbar?.tdwn?.text.toString()
                    d?.mBinding?.dldlbar?.tdwn?.apply { post {
                        text = "${progressTxt.substringBefore(' ')} 的 ${pageNumber}/${size} 页"
                    } }
                }
            }
            UPDATE_CHAPTER_PROGRESS -> d?.mBinding?.dldlbar?.tdwn?.apply { post { text = "${d?.dldChapter}/${d?.checkedChapter}" } }
            DELETE_SELECTED_CHAPTERS -> d?.deleteChapters()
            SET_DOWNLOAD_CARD_BLUE -> d?.resources?.getColor(cardBlueRes(d))?.let { d?.mBinding?.dldlbar?.cdwn?.apply { post {
                setCardBackgroundColor(it)
            } } }
            SET_DOWNLOAD_CARD_RED -> d?.resources?.getColor(cardRedRes(d))?.let { d?.mBinding?.dldlbar?.cdwn?.apply { post {
                setCardBackgroundColor(it)
            } } }
            PAGE_DOWNLOAD_RETRYING -> Toast.makeText(d, "下载${d?.tbtnlist?.get(chapterIndex)?.textOn}的第${pageNumber}页失败，尝试重新下载...", Toast.LENGTH_SHORT).show()
        }
    }
    /** 夜间用暗色卡片，避免整块浅蓝/浅红在暗色页面里过亮。 */
    private fun cardBlueRes(d: DlActivity?): Int =
        if (d != null && NightTint.on(d)) R.color.colorBlueNight else R.color.colorBlue

    private fun cardRedRes(d: DlActivity?): Int =
        if (d != null && NightTint.on(d)) R.color.colorRedNight else R.color.colorRed

    private fun setSize(pageNow: Int, tbtnNo: Int){
        if(refreshSize || size == 0) {
            size = d?.tbtnlist?.get(tbtnNo)?.hash?.let { wmdlt?.get()?.getImgsCountByHash(it) }?:0
            refreshSize = false
        }else if(pageNow == size) refreshSize = true
    }

    companion object {
        const val INITIALIZE_LAYOUTS = -2
        const val CHAPTER_DOWNLOAD_FAILED = -1
        const val CHAPTER_DOWNLOAD_SUCCEEDED = 1
        const val TOGGLE_SELECT_ALL = 4
        const val PAGE_DOWNLOAD_FINISHED = 5
        const val UPDATE_CHAPTER_PROGRESS = 6
        const val DELETE_SELECTED_CHAPTERS = 7
        const val SET_DOWNLOAD_CARD_BLUE = 8
        const val SET_DOWNLOAD_CARD_RED = 9
        const val PAGE_DOWNLOAD_RETRYING = 10
    }
}