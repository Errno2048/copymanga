package top.fumiama.copymangaweb.tool

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import top.fumiama.copymangaweb.web.JS

/**
 * 原生界面的夜间配色。与网页端共用同一个夜间偏好（JS.NIGHT_PREF / JS.NIGHT_KEY），
 * 因此在设置页切换后，下载页等原生界面也会跟随。
 */
object NightTint {
    const val BG = 0xFF121212.toInt()
    const val SURFACE = 0xFF1C1C1C.toInt()
    const val FG = 0xFFD8D8D8.toInt()
    const val DIVIDER = 0xFF2A2A2A.toInt()
    const val LIGHT_BG = 0xFFFCFCFF.toInt()

    fun on(context: Context): Boolean =
        context.getSharedPreferences(JS.NIGHT_PREF, Context.MODE_PRIVATE)
            .getBoolean(JS.NIGHT_KEY, false)

    fun pageBg(context: Context): Int = if (on(context)) BG else LIGHT_BG

    /** 系统栏原值，用于关闭夜间时还原。 */
    class BarOrigin(val status: Int, val nav: Int, val background: Drawable?)

    fun captureBars(activity: Activity): BarOrigin = BarOrigin(
        activity.window.statusBarColor,
        activity.window.navigationBarColor,
        activity.window.decorView.background
    )

    /** 夜间时把状态栏/导航栏与窗口底色一并改黑，避免顶部与底部出现白边。 */
    fun applyBars(activity: Activity, origin: BarOrigin) {
        val night = on(activity)
        activity.window.statusBarColor = if (night) BG else origin.status
        activity.window.navigationBarColor = if (night) BG else origin.nav
        activity.window.decorView.background = if (night) ColorDrawable(BG) else origin.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity.window.insetsController?.setSystemBarsAppearance(if (night) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = activity.window.decorView.systemUiVisibility
            flags = if (night) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = flags
        }
    }
}
