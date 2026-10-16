package org.glasshouse.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/**
 * A Quick Settings tile that turns one saved TV on and off: the one chosen
 * from its card's menu, or the only one. On while the TV is on; tapping it
 * goes through the TV's server, or Wake-on-LAN for a TV that does not answer.
 */
class TvTileService : TileService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The TV's state as last read, and a command under way, for the tile's text. */
    @Volatile private var last: Shown? = null
    @Volatile private var busyText: String? = null

    override fun onStartListening() {
        super.onStartListening()
        show()
        readState()
    }

    override fun onClick() {
        super.onClick()
        val tv = TileChoice.tv(this)
        if (tv == null || !LocalNetwork.granted(this)) {
            openApp()
            return
        }
        if (busyText != null) return
        val turnOff = last?.reach == Reach.ON
        busyText = getString(if (turnOff) R.string.tv_turning_off else R.string.tv_turning_on)
        show()
        worker.execute {
            val asked = if (turnOff) TvPower.turnOff(tv) == null else TvPower.turnOn(this, tv) == TvPower.TurnOn.On
            last = (if (asked) TvPower.awaitPower(tv, on = !turnOff) else TvProbe.status(tv.link)).toShown()
            busyText = null
            main.post { show() }
        }
    }

    private fun readState() {
        val tv = TileChoice.tv(this)
        if (tv == null || !LocalNetwork.granted(this)) {
            main.post { show() }
            return
        }
        worker.execute {
            last = TvProbe.status(tv.link).toShown()
            main.post { show() }
        }
    }

    /** The tile shows the TV's name, and its state or the command under way. */
    private fun show() {
        val tile = qsTile ?: return
        val tv = TileChoice.tv(this)
        val shown = last
        tile.label = tv?.name ?: getString(R.string.tile_label)
        tile.state = when {
            tv == null -> Tile.STATE_UNAVAILABLE
            shown?.reach == Reach.ON -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = busyText ?: when {
            tv == null -> getString(R.string.tile_no_tv)
            shown == null -> getString(R.string.tv_checking)
            shown.reach == Reach.ON -> getString(R.string.tv_on)
            shown.reach == Reach.ASLEEP -> shown.label.orEmpty().ifEmpty { getString(R.string.tv_standby) }
            shown.reach == Reach.OFFLINE -> getString(R.string.tv_offline)
            else -> getString(R.string.tv_online)
        }
        tile.updateTile()
    }

    // The Intent form is the only one before Android 14, and throws from it on;
    // the version check keeps each to its own.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** Asks Android to refresh the tile, after the chosen TV changes. */
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, TvTileService::class.java))
    }
}
