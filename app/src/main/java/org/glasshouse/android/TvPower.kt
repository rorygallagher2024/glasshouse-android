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
}
