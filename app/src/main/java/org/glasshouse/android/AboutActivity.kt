package org.glasshouse.android

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Version, source code, documentation and the privacy policy the Play listing links to. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        paintSystemBars()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        padForSystemBars(findViewById(R.id.appbar), findViewById(R.id.content))

        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        val links = findViewById<LinearLayout>(R.id.links)
        addLink(links, R.string.about_source_app, R.string.url_source_app)
        addLink(links, R.string.about_source_server, R.string.url_source_server)
        addLink(links, R.string.about_docs, R.string.url_docs)
        addLink(links, R.string.about_privacy, R.string.url_privacy)
        addRow(links, getString(R.string.about_licences), getString(R.string.about_licences_detail), external = false) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_licences)
                .setMessage(R.string.about_licences_text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun addLink(parent: LinearLayout, @StringRes title: Int, @StringRes url: Int) {
        val target = getString(url)
        val uri = Uri.parse(target)
        // Shown without the scheme, as the TV list shows addresses.
        addRow(parent, getString(title), uri.host + uri.path.orEmpty().trimEnd('/'), external = true) {
            openInBrowser(uri)
        }
    }

    private fun addRow(parent: LinearLayout, title: String, detail: String, external: Boolean, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_link, parent, false)
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.detail).text = detail
        row.findViewById<View>(R.id.icon).visibility = if (external) View.VISIBLE else View.GONE
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
