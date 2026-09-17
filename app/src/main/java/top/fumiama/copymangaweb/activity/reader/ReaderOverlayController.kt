package top.fumiama.copymangaweb.activity.reader

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import top.fumiama.copymangaweb.activity.ViewMangaActivity
import top.fumiama.copymangaweb.databinding.ActivityViewmangaBinding
import top.fumiama.copymangaweb.tool.ToolsBox
import java.util.Date

class ReaderOverlayController(
    private val activity: ViewMangaActivity,
    private val binding: ActivityViewmangaBinding,
    private val toolsBox: ToolsBox,
    private val drawerOffset: () -> Float,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = DateFormat.getTimeFormat(activity)
    private var drawerVisible = false

    private val updateStatus = object : Runnable {
        @SuppressLint("SetTextI18n")
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) return
            binding.infcard.idtime.text = "${clockFormat.format(Date())}${toolsBox.week}${toolsBox.netInfo}"
            handler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }

    fun start() {
        handler.post(updateStatus)
    }

    fun toggleDrawer() {
        if (drawerVisible) hideDrawer() else showDrawer()
    }

    fun hideDrawer() {
        // 用可见性隐藏：启动时位移偏移还是 0，只靠 translationY 挡不住（下栏会一直可见）
        val offset = drawerOffset()
        Log.d("ReaderOverlay", "hide drawer offset=$offset")
        binding.infcard.apply {
            if (!drawerVisible) {
                root.visibility = android.view.View.INVISIBLE   // 初始状态：直接不显示
                idc.alpha = 0.3f
                return
            }
            ObjectAnimator.ofFloat(idc, "alpha", idc.alpha, 0.3f).setDuration(ANIMATION_DURATION_MS).start()
            ObjectAnimator.ofFloat(root, "translationY", root.translationY, offset)
                .setDuration(ANIMATION_DURATION_MS).start()
            root.postDelayed({ if (!drawerVisible) root.visibility = android.view.View.INVISIBLE }, ANIMATION_DURATION_MS)
        }
        drawerVisible = false
    }

    fun showDrawer() {
        val offset = drawerOffset()
        Log.d("ReaderOverlay", "show drawer offset=$offset")
        binding.infcard.apply {
            root.visibility = android.view.View.VISIBLE
            root.translationY = offset
            ObjectAnimator.ofFloat(idc, "alpha", idc.alpha, 0.8f).setDuration(ANIMATION_DURATION_MS).start()
            ObjectAnimator.ofFloat(root, "translationY", root.translationY, 0f).setDuration(ANIMATION_DURATION_MS).start()
        }
        drawerVisible = true
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val STATUS_UPDATE_INTERVAL_MS = 3_000L
        private const val ANIMATION_DURATION_MS = 233L
    }
}
