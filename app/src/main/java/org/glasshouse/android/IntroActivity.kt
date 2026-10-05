package org.glasshouse.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown on first launch: the app is only a client, so it says up front that
 * the TV needs the Glasshouse server, and links to how to install it.
 */
class IntroActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        paintSystemBars()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)
        padForSystemBars(findViewById(R.id.scroll))

        findViewById<View>(R.id.docs).setOnClickListener { openInBrowser(getString(R.string.url_install)) }
        findViewById<View>(R.id.start).setOnClickListener {
            markSeen(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    companion object {
        private const val PREFS = "intro"
        private const val KEY_SEEN = "seen"

        fun seen(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        private fun markSeen(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }
}
