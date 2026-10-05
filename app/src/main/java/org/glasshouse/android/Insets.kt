package org.glasshouse.android

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.ColorUtils
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

/** For a screen with no app bar: keeps [content] clear of every system bar. */
fun padForSystemBars(content: View) {
    val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val i = insets.getInsets(bars)
        v.updatePadding(left = i.left, top = i.top, right = i.right, bottom = i.bottom)
        insets
    }
}

fun isLightColour(colour: Int): Boolean = ColorUtils.calculateLuminance(colour) > 0.5

/**
 * Draws edge to edge with the status and navigation bars showing [background]
 * and icons that suit it. The scrims are transparent, and a light or dark
 * style (rather than auto) also turns off the translucent bar Android puts
 * behind three-button navigation, so the bars are the page colour exactly.
 */
fun ComponentActivity.paintSystemBars(background: Int) {
    val style = if (isLightColour(background)) {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    } else {
        SystemBarStyle.dark(Color.TRANSPARENT)
    }
    enableEdgeToEdge(style, style)
    window.decorView.setBackgroundColor(background)
}

/** The theme's page colour: black, or the dashboard's light grey. */
fun ComponentActivity.paintSystemBars() = paintSystemBars(getColor(R.color.bg))
