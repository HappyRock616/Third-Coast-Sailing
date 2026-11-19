package com.thirdcoastsailing.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.bearing
import com.thirdcoastsailing.app.celestial.distanceNm
import com.thirdcoastsailing.app.celestial.travel

/*
 * Anchor watch.
 *
 *  - Menu: an Anchor button (opens the setup screen) with an on/off switch, and a hold-to-reset arrow while an anchor
 *    point is saved. Switching on with no saved anchor drops the anchor at the boat's GPS position (0 ft offset: mark
 *    it from the bow as the anchor goes down); it needs a fresh, precise fix. The setup screen can instead place it
 *    ahead: point the top of the phone at the anchor, type the distance, and the compass bearing places it.
 *  - Chart: the anchor symbol at the anchor point, a bold red circle at the alarm radius (default 100 ft), and a
 *    dashed line of the boat's GPS positions as it swings.
 *  - Top of the chart: a large yellow box with the distance and true bearing to the anchor, "GPS fix lost" when there
 *    has been no fix for 10 minutes, and in an alarm a red border with "Alarm Silence" in bold.
 *  - Alarm: outside the radius, or no GPS fix for 10 minutes. A fix outside only within its own GPS error is checked
 *    again at once with a fresh fix (up to 30 s; no fresh fix = alarm), so one glitch doesn't ring the bell. The
 *    rules are in [AnchorRules] (pure functions, unit tested). A ship's bell struck in pairs (made in code) on the
 *    alarm sound channel, which sounds on silent and in Do Not Disturb unless alarms are blocked there, plus
 *    vibration. Silence it with either volume button while the app is on screen, by tapping the yellow box, or with
 *    "Silence 15 min" on the notification. It re-arms after 15 minutes and sounds again if still outside the circle.
 *  - Runs all night with the screen off (a foreground service, one GPS fix every 2 minutes), and keeps running when
 *    the app is swiped away: switch it off in the app or with "Stop Anchor Watch" on the notification. The anchor point,
 *    settings and the on/off state are kept on the phone; if Android stops the watch, a notification says so, and it
 *    restarts the next time the app is opened.
 */

/** The anchor watch's state and settings, shared by the screen, the chart overlay and the background service. */
object AnchorWatch {
    const val DEFAULT_RADIUS_FT = 100.0
    const val MIN_RADIUS_FT = 20.0
    const val MAX_RADIUS_FT = 2000.0
    const val FIX_MS = 2 * 60_000L                 // one GPS fix every 2 minutes; the radius is checked on each
    const val LOST_MS = 10 * 60_000L               // no fix for this long = "GPS fix lost" alarm
    const val SNOOZE_MS = 15 * 60_000L             // silence lasts this long, then the alarm re-arms
    private const val MAX_TRACE = 600              // swing trace points kept (20 hours at 2 minutes)
    private const val PREFS = "tcs_anchor"

    /** Anchor point, alarm radius and the live watch status. */
    data class State(
        val anchor: GeoPt? = null,                 // anchor position (saved on the phone)
        val setMs: Long = 0L,                      // when it was set
        val radiusFt: Double = DEFAULT_RADIUS_FT,  // alarm radius (saved)
        val offsetFt: Double = 0.0,                // distance from the phone to the anchor when it was set (saved)
        val armed: Boolean = false,                // watch switched on (saved)
        val fix: BoatFix? = null,                  // latest GPS fix from the watch
        val trace: List<GeoPt> = emptyList(),      // swing trace (memory only)
        val alarming: Boolean = false,             // bell sounding now
        val fixLost: Boolean = false,              // no fix for LOST_MS
        val snoozedUntilMs: Long = 0L,             // silenced until this time
    ) {
        /** Distance (ft) and true bearing from the boat to the anchor, or null without both. */
        fun toAnchor(): Pair<Double, Double>? {
            val a = anchor ?: return null; val f = fix ?: return null
            val from = LatLon(f.lat, f.lon); val to = LatLon(a.lat, a.lon)
            return distanceNm(from, to) * FT_PER_NM to bearing(from, to)
        }

        /** True when the latest fix is outside the alarm radius. */
        val outside: Boolean get() = (toAnchor()?.first ?: 0.0) > radiusFt
    }

    var state by mutableStateOf(State())
        private set
    private var loaded = false

    /** Saved anchor point, settings and on/off state, read once per process. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lon = p.getString("lon", null)?.toDoubleOrNull()
        val anchor = if (lat != null && lon != null) GeoPt(lat, lon) else null
        state = State(anchor = anchor, setMs = p.getLong("setMs", 0L),
            radiusFt = p.getString("radiusFt", null)?.toDoubleOrNull() ?: DEFAULT_RADIUS_FT,
            offsetFt = p.getString("offsetFt", null)?.toDoubleOrNull() ?: 0.0,
            armed = anchor != null && p.getBoolean("armed", false))
        ShipsBell.restoreVolume(context)            // the app was closed while the bell rang: put the volume back
    }

    /** Save the anchor point, radius and on/off state, so the watch survives the phone or app restarting. */
    private fun save(context: Context) {
        val s = state
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (s.anchor != null) {
                putString("lat", s.anchor.lat.toString()); putString("lon", s.anchor.lon.toString())   // full precision
                putLong("setMs", s.setMs)
            } else { remove("lat"); remove("lon"); remove("setMs") }
            putString("radiusFt", s.radiusFt.toString()); putString("offsetFt", s.offsetFt.toString())
            putBoolean("armed", s.armed)
        }.apply()
    }

    /** Change the state (main thread only) and save what's kept. */
    fun update(context: Context, f: (State) -> State) { state = f(state); save(context) }

    /** Set the alarm radius (kept between MIN_RADIUS_FT and MAX_RADIUS_FT). */
    fun setRadius(context: Context, ft: Double) =
        update(context) { it.copy(radiusFt = ft.coerceIn(MIN_RADIUS_FT, MAX_RADIUS_FT)) }

    /**
     * Drop the anchor `offsetFt` from the boat's position along `bearingDeg` (true) and start the watch.
     * 0 ft = at the boat (marked from the bow during the drop). Needs a fresh fix; false otherwise.
     */
    fun drop(context: Context, at: BoatFix, offsetFt: Double, bearingDeg: Double): Boolean {
        if (!at.fresh || !hasFineLocation(context)) return false
        val p = if (offsetFt <= 0.0) GeoPt(at.lat, at.lon)
        else travel(LatLon(at.lat, at.lon), bearingDeg, offsetFt / FT_PER_NM).let { GeoPt(it.lat, it.lon) }
        update(context) {
            it.copy(anchor = p, setMs = System.currentTimeMillis(), offsetFt = offsetFt.coerceAtLeast(0.0),
                fix = at, trace = listOf(GeoPt(at.lat, at.lon)), alarming = false, fixLost = false, snoozedUntilMs = 0L)
        }
        return arm(context, at)
    }

    /**
     * Switch the watch on: with a saved anchor it resumes on that point; with none it drops the anchor at `fix`
     * (0 ft offset). Returns false without precise location permission, or with no anchor and no fresh fix.
     */
    fun arm(context: Context, fix: BoatFix?): Boolean {
        if (!hasFineLocation(context)) return false
        if (state.anchor == null) {
            val f = fix?.takeIf { it.fresh } ?: return false
            update(context) { it.copy(anchor = GeoPt(f.lat, f.lon), setMs = System.currentTimeMillis(), offsetFt = 0.0,
                fix = f, trace = listOf(GeoPt(f.lat, f.lon))) }
        }
        update(context) { it.copy(armed = true, alarming = false, fixLost = false, snoozedUntilMs = 0L, fix = it.fix ?: fix) }
        ShipsBell.prepare()                         // make the bell sound now, so an alarm starts at once
        AnchorService.start(context)
        return true
    }

    /** The app opened with the watch switched on (e.g. after Android stopped it): start the service again. */
    fun resume(context: Context) { if (state.armed && state.anchor != null) AnchorService.start(context) }

    /** Switch the watch off (the anchor point stays saved). */
    fun disarm(context: Context) {
        update(context) { it.copy(armed = false, alarming = false, fixLost = false, snoozedUntilMs = 0L) }
        AnchorService.stop(context)
    }

    /** Hold-to-reset: stop the watch and forget the anchor point and its trace (radius stays). */
    fun reset(context: Context) {
        update(context) { State(radiusFt = it.radiusFt) }          // armed = false first, so the service stops quietly
        AnchorService.stop(context)
    }

    /** From the service: a new GPS fix. */
    internal fun onFix(context: Context, f: BoatFix) = update(context) {
        it.copy(fix = f, fixLost = false, trace = (it.trace + GeoPt(f.lat, f.lon)).takeLast(MAX_TRACE))
    }
}

/** The anchor alarm's decisions, kept free of Android so they can be unit tested. */
object AnchorRules {
    /** What to do with a new GPS fix. */
    enum class OnFix { CHECK, RECHECK }

    /**
     * A fix `distFt` from the anchor with GPS error `errFt`: outside the circle only within its own error (could be a
     * glitch) asks for a quick recheck with a fresh fix, unless a recheck is running, the bell is already sounding or
     * it is silenced; anything else is checked as it is.
     */
    fun onFix(distFt: Double?, errFt: Double, radiusFt: Double, rechecking: Boolean, alarming: Boolean, snoozed: Boolean): OnFix {
        val borderline = distFt != null && distFt > radiusFt && distFt - errFt <= radiusFt
        return if (borderline && !rechecking && !alarming && !snoozed) OnFix.RECHECK else OnFix.CHECK
    }

    /** True when the bell should sound: outside (and not waiting on a recheck) or the fix is lost, unless silenced. */
    fun shouldAlarm(outside: Boolean, rechecking: Boolean, fixLost: Boolean, snoozed: Boolean): Boolean =
        ((outside && !rechecking) || fixLost) && !snoozed

    /** True when no fix has come for LOST_MS. */
    fun fixLost(nowMs: Long, lastFixMs: Long): Boolean = nowMs - lastFixMs >= AnchorWatch.LOST_MS

    /** When the next timer check is due: when the fix would count as lost, or when a silence ends, whichever is first. */
    fun nextCheckMs(nowMs: Long, lastFixMs: Long, snoozedUntilMs: Long): Long {
        val lostAt = lastFixMs + AnchorWatch.LOST_MS + 5_000L
        val snoozeEnd = snoozedUntilMs.takeIf { it > nowMs }
        return listOfNotNull(lostAt.takeIf { it > nowMs }, snoozeEnd).minOrNull() ?: (nowMs + AnchorWatch.LOST_MS)
    }

    /** Radius typed in the setup screen: null while it is still being typed (below the minimum), else kept in range. */
    fun typedRadius(text: String): Double? =
        text.toDoubleOrNull()?.takeIf { it >= AnchorWatch.MIN_RADIUS_FT }?.coerceAtMost(AnchorWatch.MAX_RADIUS_FT)
}
