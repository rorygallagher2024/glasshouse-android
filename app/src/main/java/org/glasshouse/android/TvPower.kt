package org.glasshouse.android

import android.content.Context
import android.os.SystemClock

/**
 * Turning a TV on and off, for the list, the dashboard and the Quick Settings
 * tile alike. Everything here blocks: call it off the main thread.
 */
object TvPower {

    sealed class TurnOn {
        object On : TurnOn()

        /** The server answered and declined, with its reason (empty if it gave none). */
        data class Refused(val reason: String) : TurnOn()

        /** Nothing answered: no MAC to wake it by, or it did not wake within the wait. */
        object NoAnswer : TurnOn()
    }

    private const val WAKE_TIMEOUT_MS = 60_000L
    private const val WAKE_RESEND_MS = 5_000L
    private const val WAKE_POLL_MS = 1_000L

    /**
     * A TV whose server answers is dark but awake (Active Standby, Always-on,
     * Screen off) and is asked by its own server. One that does not answer
     * gets Wake-on-LAN, resent every few seconds in case one is missed, until
     * the server comes back; a TV coming out of standby takes a while to
     * bring its network and the server back. [keepGoing] ends the wait early.
     */
    fun turnOn(context: Context, tv: Tv, keepGoing: () -> Boolean = { true }): TurnOn {
        if (TvProbe.check(tv.link) == TvProbe.Reach.ANSWERS) {
            val refused = TvProbe.powerOn(tv.link)
            return if (refused == null) TurnOn.On else TurnOn.Refused(refused)
        }
        val mac = tv.mac ?: return TurnOn.NoAnswer
        val appContext = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + WAKE_TIMEOUT_MS
        var nextSend = 0L
        while (keepGoing() && SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSend) {
                WakeOnLan.send(appContext, mac, tv.link.host)
                nextSend = now + WAKE_RESEND_MS
            }
            if (TvProbe.check(tv.link) == TvProbe.Reach.ANSWERS) return TurnOn.On
            try {
                Thread.sleep(WAKE_POLL_MS)
            } catch (e: InterruptedException) {
                return TurnOn.NoAnswer
            }
        }
        return TurnOn.NoAnswer
    }

    /** Null once the TV has been asked to turn off, or the server's reason. */
    fun turnOff(tv: Tv): String? = TvProbe.powerOff(tv.link)

    private const val SETTLE_TIMEOUT_MS = 25_000L
    private const val SETTLE_POLL_MS = 1_000L

    /**
     * Asks the TV every second until it reports the power state just asked
     * for, and returns what it last said. A TV keeps reporting Standby for a
     * few seconds after powerOn succeeds, and On for a moment after powerOff,
     * so one look straight after the command shows the state it is leaving.
     */
    fun awaitPower(tv: Tv, on: Boolean, keepGoing: () -> Boolean = { true }): TvProbe.Status {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var status = TvProbe.status(tv.link)
        while (keepGoing() && SystemClock.elapsedRealtime() < deadline && isOn(status) != on) {
            try {
                Thread.sleep(SETTLE_POLL_MS)
            } catch (e: InterruptedException) {
                break
            }
            status = TvProbe.status(tv.link)
        }
        return status
    }

    private fun isOn(status: TvProbe.Status) = status.toShown().reach == Reach.ON
}
