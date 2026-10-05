package org.glasshouse.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/** One TV's web dashboard, full screen, with a switch to the other saved TVs. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var store: TvStore
    private lateinit var tv: Tv
    private lateinit var web: WebView
    private lateinit var appBar: AppBarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retry: View

    private val probes = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Bumped by every load, so a probe or timeout from an earlier one is ignored. */
    private var loadId = 0

    private var controlsShown = true
    private val hideControls = Runnable { setControlsShown(false) }

    /** Scroll since the last change of direction, in pixels. */
    private var scrolled = 0
    private var scrollSlop = 0

    /** Gives up on a page that has started but not arrived. */
    private val loadTimeout = Runnable {
        web.stopLoading()
        showError()
    }

    /** The foreground that suits the bars' current colour, for the menu icons. */
    private var barForeground = 0

    /** Set when switching TVs, so Back does not return to the previous one. */
    private var clearHistoryOnLoad = false

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
        appBar = findViewById(R.id.appbar)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        content = findViewById(R.id.content)
        scrollSlop = (SCROLL_SLOP_DP * resources.displayMetrics.density).toInt()
        fitAroundCutout()
        goImmersive()

        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        retry = findViewById(R.id.retry)
        retry.setOnClickListener { load() }

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
        load()
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
        web.setOnScrollChangeListener { _, _, y, _, oldY -> onPageScrolled(y - oldY) }
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
                setControlsShown(true)
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
                peekControls()
                view.evaluateJavascript(WATCH_BACKGROUND, null)
                if (clearHistoryOnLoad) {
                    clearHistoryOnLoad = false
                    view.clearHistory()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    main.removeCallbacks(loadTimeout)
                    showError()
                }
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
        supportActionBar?.title = target.name
        supportActionBar?.subtitle = target.link.label
        showStatus(getString(R.string.dash_connecting, target.name), canRetry = false)
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        setControlsShown(true)
        probes.execute {
            val answers = TvProbe.answers(target.link)
            main.post {
                if (id != loadId || isDestroyed) return@post
                progress.visibility = View.INVISIBLE
                progress.isIndeterminate = false
                if (answers) {
                    web.loadUrl(target.link.dashboardUrl())
                    main.postDelayed(loadTimeout, PAGE_TIMEOUT_MS)
                } else {
                    showError()
                }
            }
        }
    }

    private fun showPage() {
        errorView.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    private fun showStatus(text: String, canRetry: Boolean) {
        paintBars(getColor(R.color.bg))
        web.visibility = View.INVISIBLE
        errorText.text = text
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun showError() {
        progress.visibility = View.INVISIBLE
        showStatus(getString(R.string.dash_unreachable, tv.name, tv.link.label), canRetry = true)
        setControlsShown(true)
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
     * The page keeps clear of the camera cutout and the keyboard only; the
     * system bars, when swiped in, sit over it. The floating bar goes below
     * the status bar when that is showing, and comes in with it.
     */
    private fun fitAroundCutout() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.updatePadding(left = cutout.left, top = maxOf(cutout.top, status.top), right = cutout.right)
            content.updatePadding(
                left = cutout.left,
                top = cutout.top,
                right = cutout.right,
                bottom = maxOf(cutout.bottom, ime.bottom),
            )
            if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) peekControls()
            insets
        }
    }

    /** While connecting, loading or offline the bar stays: it is the way back. */
    private fun controlsPinned() =
        errorView.visibility == View.VISIBLE || progress.visibility == View.VISIBLE

    /** Shows the bar, then lets it slide away after a pause unless pinned. */
    private fun peekControls() {
        setControlsShown(true)
        if (!controlsPinned()) main.postDelayed(hideControls, CONTROLS_MS)
    }

    private fun setControlsShown(shown: Boolean) {
        main.removeCallbacks(hideControls)
        if (!shown && controlsPinned()) return
        if (shown == controlsShown) return
        controlsShown = shown
        appBar.animate().cancel()
        if (shown) {
            appBar.visibility = View.VISIBLE
            appBar.animate().translationY(0f).setDuration(CONTROLS_ANIM_MS)
        } else {
            appBar.animate().translationY(-appBar.height.toFloat()).setDuration(CONTROLS_ANIM_MS)
                // Invisible once gone, so it takes no touches or screen-reader focus.
                .withEndAction { if (!controlsShown) appBar.visibility = View.INVISIBLE }
        }
    }

    /** Scrolling up brings the bar back, as in a browser; scrolling down puts it away. */
    private fun onPageScrolled(dy: Int) {
        if (dy == 0) return
        if ((dy > 0) != (scrolled > 0)) scrolled = 0
        scrolled += dy
        when {
            scrolled < -scrollSlop -> { scrolled = 0; peekControls() }
            scrolled > scrollSlop -> { scrolled = 0; setControlsShown(false) }
        }
    }

    /**
     * The dashboard has its own Auto, Dark and Light setting, so the bars take
     * the page's actual background rather than the phone's theme, and the
     * header and navigation bar run into the page without a seam.
     */
    private fun paintBars(background: Int) {
        val fg = if (isLightColour(background)) Color.BLACK else Color.WHITE
        barForeground = fg
        paintSystemBars(background)
        appBar.setBackgroundColor(background)
        web.setBackgroundColor(background)
        toolbar.setTitleTextColor(fg)
        toolbar.setSubtitleTextColor(ColorUtils.setAlphaComponent(fg, 0x99))
        toolbar.navigationIcon = toolbar.navigationIcon?.mutate()?.apply { setTint(fg) }
        toolbar.overflowIcon = toolbar.overflowIcon?.mutate()?.apply { setTint(fg) }
        progress.setIndicatorColor(fg)
        invalidateOptionsMenu()
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

    private fun switchTo(next: Tv) {
        if (next.id == tv.id) return
        tv = next
        clearHistoryOnLoad = true
        load()
    }

    private fun chooseTv() {
        val tvs = store.all()
        val names = tvs.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dash_switch)
            .setSingleChoiceItems(names, tvs.indexOfFirst { it.id == tv.id }) { dialog, which ->
                dialog.dismiss()
                switchTo(tvs[which])
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dashboard, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_switch).apply {
            isVisible = store.all().size > 1
            icon = icon?.mutate()?.apply { setTint(barForeground) }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    // Keeps the bar while its menu is open.
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        main.removeCallbacks(hideControls)
        return super.onMenuOpened(featureId, menu)
    }

    override fun onPanelClosed(featureId: Int, menu: Menu) {
        super.onPanelClosed(featureId, menu)
        peekControls()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_switch -> { chooseTv(); true }
        R.id.action_reload -> {
            if (errorView.visibility == View.VISIBLE) load() else web.reload()
            true
        }
        else -> super.onOptionsItemSelected(item)
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

        /** For a server that answered the probe but then stalls on the page. */
        private const val PAGE_TIMEOUT_MS = 15_000L

        private const val CONTROLS_MS = 3_000L
        private const val CONTROLS_ANIM_MS = 180L
        private const val SCROLL_SLOP_DP = 24

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
