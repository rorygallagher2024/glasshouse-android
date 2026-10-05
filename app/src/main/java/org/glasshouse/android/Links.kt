package org.glasshouse.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens [uri] in the phone's browser, or says no app can. */
fun Context.openInBrowser(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.dash_no_app, Toast.LENGTH_SHORT).show()
    }
}

fun Context.openInBrowser(url: String) = openInBrowser(Uri.parse(url))
