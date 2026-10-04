package org.glasshouse.android

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/*
 * Targeting API 35 draws every activity edge to edge on Android 15, so the
 * app bar is padded below the status bar and the content clear of the
 * navigation bar and the keyboard (adjustResize no longer does the latter).
 */
fun padForSystemBars(appBar: View, content: View) {
    val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
        val i = insets.getInsets(bars)
        v.updatePadding(left = i.left, top = i.top, right = i.right)
        insets
    }
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val i = insets.getInsets(bars or WindowInsetsCompat.Type.ime())
        v.updatePadding(left = i.left, right = i.right, bottom = i.bottom)
        insets
    }
}
