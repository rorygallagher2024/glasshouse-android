package org.glasshouse.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/** One TV's web dashboard, full screen, with a way back to the list of TVs. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var store: TvStore
    private lateinit var tv: Tv
    private lateinit var web: WebView
    private lateinit var strip: View
    private lateinit var back: ImageButton
    private lateinit var content: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retry: View
    private lateinit var wakeButton: View
    private lateinit var refresh: SwipeRefreshLayout

    private val probes = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Bumped by every load, so a probe or timeout from an earlier one is
     * ignored. Read by the wake loop on the probe thread.
     */
    @Volatile private var loadId = 0

    /** Gives up on a page that has started but not arrived. */
    private val loadTimeout = Runnable {
        web.stopLoading()
        showError()
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        paintSystemBars()
        super.onCreate(savedInstanceState)
        store = TvStore(this)
        val saved = intent.getStringExtra(EXTRA_TV_ID)?.let { store.get(it) }
        if (saved == null) {
            finish()
            return
        }
        tv = saved

        setContentView(R.layout.activity_dashboard)
        strip = findViewById(R.id.strip)
        back = findViewById(R.id.back)
        back.setOnClickListener { finish() }
        content = findViewById(R.id.content)
        fitAroundCutout()
        goImmersive()

        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        retry = findViewById(R.id.retry)
        retry.setOnClickListener { load() }
        wakeButton = findViewById(R.id.wake)
        wakeButton.setOnClickListener { wake() }
        refresh = findViewById(R.id.refresh)
        refresh.setOnRefreshListener { web.reload() }

        setUpWebView()
        paintBars(getColor(R.color.bg))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) wake() else load()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            // The dashboard keeps the token and its theme in localStorage.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Lets the dashboard tell it is in the app, should it need to.
            userAgentString = "$userAgentString Glasshouse-Android/${BuildConfig.VERSION_NAME}"
        }
        // Only the TV's own pages load here, and all this can do is recolour the bars.
        web.addJavascriptInterface(PageBridge(), "GlasshouseApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The TV's own pages stay here; any other link opens in a browser.
                if (sameOrigin(request.url)) return false
                openInBrowser(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            // Committed is the first moment the page's theme is applied, so
            // the bars change with the first paint rather than after loading.
            override fun onPageCommitVisible(view: WebView, url: String?) {
                main.removeCallbacks(loadTimeout)
                showPage()
                view.evaluateJavascript(WATCH_BACKGROUND, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.INVISIBLE
                refresh.isRefreshing = false
                view.evaluateJavascript(WATCH_BACKGROUND, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    main.removeCallbacks(loadTimeout)
                    showError()
                }
            }

            // Never proceeds past a bad certificate: the page would carry the token.
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                main.removeCallbacks(loadTimeout)
                showUntrusted()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
            }

            // Without this, the sideload tab's package picker does nothing.
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    pickFile.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun sameOrigin(url: Uri): Boolean {
        val here = TvLink.parse(url.toString()) ?: return false
        return here.origin == tv.link.origin
    }

    /**
     * Checks the TV answers before handing the WebView its address, so an
     * offline TV shows as such within seconds instead of as a blank page.
     */
    private fun load() {
        val id = ++loadId
        val target = tv
        main.removeCallbacks(loadTimeout)
        if (!LocalNetwork.granted(this)) {
            showStatus(getString(R.string.dash_lan_denied), canRetry = true)
            return
        }
        showStatus(getString(R.string.dash_connecting, target.name), canRetry = false)
        showBusy()
        probes.execute {
            val result = TvProbe.check(target.link)
            main.post {
                if (id != loadId || isDestroyed) return@post
                when (result) {
                    TvProbe.Reach.ANSWERS -> {
                        progress.visibility = View.INVISIBLE
                        progress.isIndeterminate = false
                        web.loadUrl(target.link.dashboardUrl())
                        main.postDelayed(loadTimeout, PAGE_TIMEOUT_MS)
                    }
                    TvProbe.Reach.NO_ANSWER -> showError()
                    TvProbe.Reach.UNTRUSTED_CERT -> showUntrusted()
                }
            }
        }
    }

    /**
     * Turns the TV on, then opens its dashboard. A TV whose server answers is
     * dark but awake (Active Standby, Always-on, Screen off) and is asked by
     * its own server; one that does not answer gets Wake-on-LAN, resent every
     * few seconds in case one is missed, until the server comes back.
     */
    private fun wake() {
        val id = ++loadId
        val target = tv
        val appContext = applicationContext
        main.removeCallbacks(loadTimeout)
        showStatus(getString(R.string.dash_waking, target.name), canRetry = false)
        showBusy()
        probes.execute {
            if (TvProbe.check(target.link) == TvProbe.Reach.ANSWERS) {
                val refused = TvProbe.powerOn(target.link)
                main.post {
                    if (id != loadId || isDestroyed) return@post
                    if (refused == null) {
                        load()
                    } else {
                        progress.visibility = View.INVISIBLE
                        val reason = refused.ifEmpty { getString(R.string.dash_power_on_no_reply) }
                        showStatus(getString(R.string.dash_power_on_refused, target.name, reason), canRetry = true, canWake = true)
                    }
                }
                return@execute
            }
            val mac = target.mac
            if (mac == null) {
                main.post { if (id == loadId && !isDestroyed) showError() }
                return@execute
            }
            val deadline = SystemClock.elapsedRealtime() + WAKE_TIMEOUT_MS
            var nextSend = 0L
            var answered = false
            while (!answered && id == loadId && SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSend) {
                    WakeOnLan.send(appContext, mac, target.link.host)
                    nextSend = now + WAKE_RESEND_MS
                }
                answered = TvProbe.check(target.link) == TvProbe.Reach.ANSWERS
                if (!answered) {
                    try {
                        Thread.sleep(WAKE_POLL_MS)
                    } catch (e: InterruptedException) {
                        return@execute
                    }
                }
            }
            main.post {
                if (id != loadId || isDestroyed) return@post
                if (answered) load() else showWakeFailed()
            }
        }
    }

    private fun showBusy() {
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
    }

    private fun showPage() {
        errorView.visibility = View.GONE
        web.visibility = View.VISIBLE
        refresh.isEnabled = true
    }

    private fun showStatus(text: String, canRetry: Boolean, canWake: Boolean = false) {
        paintBars(getColor(R.color.bg))
        web.visibility = View.INVISIBLE
        refresh.isRefreshing = false
        refresh.isEnabled = false
        errorText.text = text
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        wakeButton.visibility = if (canWake) View.VISIBLE else View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun showError() {
        progress.visibility = View.INVISIBLE
        showStatus(getString(R.string.dash_unreachable, tv.name, tv.link.label), canRetry = true, canWake = tv.mac != null)
    }

    private fun showUntrusted() {
        progress.visibility = View.INVISIBLE
        showStatus(getString(R.string.dash_untrusted, tv.name, tv.link.label), canRetry = true)
    }

    private fun showWakeFailed() {
        progress.visibility = View.INVISIBLE
        // The TV's own setting, as last read: off means no packet will wake it.
        val text = if (tv.wakeOnLan == false) R.string.dash_wake_setting_off else R.string.dash_wake_failed
        showStatus(getString(text, tv.name), canRetry = true, canWake = true)
    }

    /**
     * Full screen: the system bars stay hidden until swiped in from an edge,
     * and come back hidden by themselves. Reapplied on regaining focus, since
     * a dialog or another app can bring them back.
     */
    private fun goImmersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * The strip takes the camera cutout's height, which the page could not
     * use, so the back button costs the page nothing on a phone with one; on
     * a screen without, it is the button's own height. The page keeps clear of
     * the keyboard; the system bars, when swiped in, sit over it.
     */
    private fun fitAroundCutout() {
        val minStrip = resources.getDimensionPixelSize(R.dimen.dash_strip_min)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            strip.updateLayoutParams { height = maxOf(cutout.top, minStrip) }
            strip.updatePadding(left = cutout.left, right = cutout.right)
            content.updatePadding(left = cutout.left, right = cutout.right, bottom = maxOf(cutout.bottom, ime.bottom))
            insets
        }
    }

    /**
     * The dashboard has its own Auto, Dark and Light setting, so the bars take
     * the page's actual background rather than the phone's theme, and the
     * strip and the swiped-in system bars run into the page without a seam.
     */
    private fun paintBars(background: Int) {
        val fg = if (isLightColour(background)) Color.BLACK else Color.WHITE
        paintSystemBars(background)
        strip.setBackgroundColor(background)
        web.setBackgroundColor(background)
        back.setColorFilter(fg)
        progress.setIndicatorColor(fg)
        refresh.setColorSchemeColors(fg)
        refresh.setProgressBackgroundColorSchemeColor(background)
    }

    private inner class PageBridge {
        // Called on the WebView's own thread.
        @JavascriptInterface
        fun pageBackground(css: String) {
            val colour = CssColour.parse(css) ?: return
            runOnUiThread {
                if (!isDestroyed && errorView.visibility != View.VISIBLE) paintBars(colour)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // pauseTimers also stops the dashboard's polling, which would otherwise
    // keep asking the TV for stats with the app in the background.
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            web.onResume()
            web.resumeTimers()
        }
    }

    override fun onPause() {
        if (::web.isInitialized) {
            web.onPause()
            web.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(loadTimeout)
        probes.shutdownNow()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TV_ID = "tv_id"

        /** Set by the list's power button: wake the TV rather than just connect. */
        const val EXTRA_WAKE = "wake"

        private const val WAKE_TIMEOUT_MS = 60_000L
        private const val WAKE_RESEND_MS = 5_000L
        private const val WAKE_POLL_MS = 1_000L

        /** For a server that answered the probe but then stalls on the page. */
        private const val PAGE_TIMEOUT_MS = 15_000L

        /**
         * Reports the page's background now and whenever the dashboard's
         * theme switch sets or clears data-theme on the root element. Run on
         * commit and again on finish; the flag keeps it to one observer.
         */
        private val WATCH_BACKGROUND = """
            (function () {
              var root = document.documentElement;
              function report() { GlasshouseApp.pageBackground(getComputedStyle(root).backgroundColor); }
              report();
              if (window.__glasshouseWatch) return;
              window.__glasshouseWatch = new MutationObserver(report);
              window.__glasshouseWatch.observe(root, { attributes: true, attributeFilter: ['data-theme', 'class', 'style'] });
            })();
        """.trimIndent()
    }
}
