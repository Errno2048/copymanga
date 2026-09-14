package top.fumiama.copymangaweb.web

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import top.fumiama.copymangaweb.R

class WebViewClient(private val context: Context, jsFileName: String):WebViewClient() {
    // evaluateJavascript() 接收的是脚本本身，而不是 javascript: URL；
    // 资源文件以 "javascript:" 开头，这里去掉前缀。
    // 旧实现用 loadUrl("javascript:...") 会把脚本当 URL 解析，
    // 脚本中的反斜杠与 "//" 会被 URL 规则改写，导致注入静默失败。
    private val js = context.assets.open(jsFileName).readBytes().decodeToString()
        .removePrefix("javascript:").trim()

    private var mainFrameFailures = 0
    private var pageHadError = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.d("MyWC", "Load URL: $url")
        pageHadError = false
        val target = url ?: return
        // 只拦截指向站外 http(s) 的导航。
        // about:blank / data: / blob: 等常见于站内 iframe，而 onPageStarted 无法区分
        // 主框架与子框架——若在这里拦截并 goBack()，主框架会被退回历史首项（空白页）。
        if (!target.startsWith("http://") && !target.startsWith("https://")) return
        // onPageFinished 可能被挂住的第三方资源拖住，这里兜底保证注入一定发生
        view?.postDelayed({
            if (!injectedUrls.contains(target)) inject(view, target)
        }, 1500)
        if (!Mirrors.allows(target)) {
            view?.goBack()
            Toast.makeText(context, R.string.blocked_ad, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != true) return
        pageHadError = true
        val next = Mirrors.advance(mainFrameFailures) ?: return
        mainFrameFailures++
        Log.d("MyWC", "main frame failed, switch mirror -> $next")
        Toast.makeText(context, "线路不可用，已自动切换", Toast.LENGTH_SHORT).show()
        view?.post { view.loadUrl(next) }
    }

    private val injectedUrls = HashSet<String>()

    /**
     * 注入脚本。注意不能只在 onPageFinished 里注入：站点会引入若干第三方资源，
     * 只要有一个挂住，load 事件就会长时间不来、onPageFinished 不触发，脚本便始终没有注入
     * （表现为夜间/小说/个人页等功能全部失效）。因此 onPageStarted 之后再挂一个延迟兜底。
     */
    private fun inject(view: WebView?, url: String?) {
        if (view == null) return
        if (url != null) {
            if (injectedUrls.size > 32) injectedUrls.clear()
            injectedUrls.add(url)
        }
        view.evaluateJavascript(js, null)
        Log.d("MyWC", "Inject JS into: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (!pageHadError) {
            mainFrameFailures = 0
            url?.let { Mirrors.markWorking(context, it) }
        }
        Handler(Looper.getMainLooper()).postDelayed({ inject(view, url) }, 500)
        super.onPageFinished(view, url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        request?.requestHeaders?.set("Access-Control-Allow-Origin", "*")
        return super.shouldInterceptRequest(view, request)
    }
}
