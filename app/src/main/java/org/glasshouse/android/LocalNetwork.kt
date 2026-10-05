package org.glasshouse.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 asks the user before an app reaches devices on the local network,
 * and every TV is one. Earlier versions grant it with INTERNET.
 */
object LocalNetwork {

    /** Manifest.permission.ACCESS_LOCAL_NETWORK, which only exists from API 37. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ANDROID_17 = 37

    fun needsAsking(): Boolean = Build.VERSION.SDK_INT >= ANDROID_17

    fun granted(context: Context): Boolean =
        !needsAsking() || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
