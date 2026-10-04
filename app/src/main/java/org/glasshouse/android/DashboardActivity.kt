package org.glasshouse.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** One TV's web dashboard, full screen, with a switch to the other saved TVs. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var store: TvStore
    private lateinit var tv: Tv
    private lateinit var web: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    /** Set when switching TVs, so Back does not return to the previous one. */
    private var clearHistoryOnLoad = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = TvStore(this)
        val saved = intent.getStringExtra(EXTRA_TV_ID)?.let { store.get(it) }
        if (saved == null) {
            finish()
            return
        }
        tv = saved

        setContentView(R.layout.activity_dashboard)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        padForSystemBars(findViewById(R.id.appbar), findViewById(R.id.content))

        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        findViewById<View>(R.id.retry).setOnClickListener { load() }

        setUpWebView()
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
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The TV's own pages stay here; any other link opens in a browser.
                if (sameOrigin(request.url)) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@DashboardActivity, R.string.dash_no_app, Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.INVISIBLE
                if (clearHistoryOnLoad) {
                    clearHistoryOnLoad = false
                    view.clearHistory()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError()
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

    private fun load() {
        supportActionBar?.title = tv.name
        supportActionBar?.subtitle = tv.link.label
        errorView.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(tv.link.dashboardUrl())
    }

    private fun showError() {
        web.visibility = View.INVISIBLE
        errorText.text = getString(R.string.dash_unreachable, tv.name, tv.link.label)
        errorView.visibility = View.VISIBLE
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
        menu.findItem(R.id.action_switch).isVisible = store.all().size > 1
        return super.onPrepareOptionsMenu(menu)
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
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TV_ID = "tv_id"
    }
}
