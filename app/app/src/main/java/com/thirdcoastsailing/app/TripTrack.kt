package com.thirdcoastsailing.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * The trip track, kept for the whole app process (not in the screen), so a screen change (dark theme at sunset,
 * font size, language) or going back to the home screen doesn't lose it. The points are also written to a small file
 * as they are added, so the track survives the app being closed or the phone restarting; after that, recording is
 * off until switched on again (the clock keeps the time up to the last point).
 *
 * Points come from the 1-second GPS while the app is on screen ([record]) and from [TrackService]'s 5-minute fixes
 * while it isn't ([addBackground], drained into the track when the app comes back).
 */
object TripTrack {
    private const val FILE = "trip_track.csv"
    private const val PREFS = "tcs_trip"

    /** Track points (Compose state: the chart redraws when one is added). */
    val points = mutableStateListOf<GeoPt>()
    /** Recording on. Off keeps the line and stops the clock. */
    var on by mutableStateOf(false)
        private set
    /** Distance along the track (nm). */
    var nm by mutableDoubleStateOf(0.0)
        private set

    private var runStartMs = 0L            // when the current recording stretch began
    private var elapsedBeforeMs = 0L       // recording time from earlier stretches
    private var lastFixMs = 0L             // GPS time of the newest recorded fix
    private var jumps = 0                  // impossible jumps in a row (kept off the track)
    private val avg = ArrayDeque<BoatFix>()        // last 3 fixes, averaged into each track point
    private val background = mutableListOf<BoatFix>()
    private val io = Executors.newSingleThreadExecutor()   // file writes, in order, off the main thread
    private var loaded = false
    private var cleared = false            // reset before the saved points finished loading: don't bring them back
    private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null

    /** Read the saved track once per process. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        dir = context.filesDir
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        // the saved points are read off the main thread (a long trip is a big file) and put in front of any new ones
        val f = File(context.filesDir, FILE)
        io.execute {
            val pts = if (f.exists()) runCatching {
                f.readLines().mapNotNull { line ->
                    val parts = line.split(',')
                    val lat = parts.getOrNull(0)?.toDoubleOrNull(); val lon = parts.getOrNull(1)?.toDoubleOrNull()
                    if (lat != null && lon != null) GeoPt(lat, lon) else null
                }
            }.getOrDefault(emptyList()) else emptyList()
            if (pts.isNotEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).post { if (!cleared) points.addAll(0, pts) }
        }
        nm = p.getString("nm", null)?.toDoubleOrNull() ?: 0.0
        lastFixMs = p.getLong("lastFixMs", 0L)
        elapsedBeforeMs = p.getLong("elapsedBeforeMs", 0L)
        // the app was closed while recording: count the stretch up to the last point, recording off
        val wasOnSince = p.getLong("runStartMs", 0L)
        if (p.getBoolean("on", false) && wasOnSince > 0L && lastFixMs > wasOnSince) elapsedBeforeMs += lastFixMs - wasOnSince
        saveMeta()
    }

    /** Recording time so far (ms). */
    fun elapsedMs(nowMs: Long): Long = elapsedBeforeMs + if (on) (nowMs - runStartMs).coerceAtLeast(0) else 0L

    /** Label next to the track ("12.4 nm · 1:23"), or null when there is no track. */
    fun label(nowMs: Long): String? = if (points.isEmpty()) null else trackLabel(nm, elapsedMs(nowMs))

    /** Switch recording on or off (on also starts the 5-minute background fixes). */
    fun setOn(context: Context, value: Boolean) {
        if (value == on) return
        val now = System.currentTimeMillis()
        if (value) {
            runStartMs = now
            TrackService.start(context)
        } else {
            elapsedBeforeMs += (now - runStartMs).coerceAtLeast(0)
            TrackService.stop(context)
        }
        on = value
        saveMeta()
    }

    /** The app came back: restart the background sampling if recording is on (it stops when the app is swiped away). */
    fun resume(context: Context) { if (on) TrackService.start(context) }

    /** Clear the track (recording stays as it is). */
    fun reset() {
        cleared = true
        points.clear(); nm = 0.0; lastFixMs = 0L; jumps = 0; avg.clear(); drainBackground()
        elapsedBeforeMs = 0L; runStartMs = System.currentTimeMillis()
        val d = dir
        io.execute { runCatching { d?.let { File(it, FILE).delete() } } }
        saveMeta()
    }

    /** From [TrackService]: a 5-minute fix taken while the app wasn't on screen. */
    @Synchronized fun addBackground(f: BoatFix) { background.add(f) }

    /** The background fixes taken since the last call, oldest first. */
    @Synchronized fun drainBackground(): List<BoatFix> = background.sortedBy { it.time }.also { background.clear() }

    /**
     * Add the fix as a point once the boat has moved far enough (only while recording). The step from the last point
     * always counts, so a gap is spanned by a straight line and its distance included.
     */
    fun record(f: BoatFix) {
        if (!on || f.time <= lastFixMs) return                // not recording, or older than what's on the line
        // impossible jumps (GPS glitches) stay off the line; a few in a row means the GPS really moved on, so accept
        if (trackStep(points.lastOrNull(), lastFixMs, f, jumps) is TrackDecision.Jump) { jumps++; return }
        // moving average of the last 3 fixes (started fresh after a gap, e.g. the 5-minute background fixes)
        if (avg.isNotEmpty() && f.time - avg.last().time > TRACK_AVG_GAP_MS) avg.clear()
        avg.addLast(f); if (avg.size > 3) avg.removeFirst()
        val a = f.copy(lat = avg.sumOf { it.lat } / avg.size, lon = avg.sumOf { it.lon } / avg.size)
        when (val step = trackStep(points.lastOrNull(), lastFixMs, a, TRACK_JUMP_ACCEPT_AFTER)) {
            is TrackDecision.Jump -> return
            is TrackDecision.TooClose -> { jumps = 0; return }
            is TrackDecision.Add -> {
                jumps = 0
                val p = GeoPt(a.lat, a.lon)
                points.add(p)
                nm += step.nm
                lastFixMs = f.time
                val d = dir
                io.execute { runCatching { d?.let { File(it, FILE).appendText("${p.lat},${p.lon}\n") } } }
                saveMeta()
            }
        }
    }

    /** Save the distance, clock and recording state (small, so on every change). */
    private fun saveMeta() {
        prefs?.edit()?.putString("nm", nm.toString())?.putLong("lastFixMs", lastFixMs)
            ?.putLong("elapsedBeforeMs", elapsedBeforeMs)?.putLong("runStartMs", runStartMs)?.putBoolean("on", on)?.apply()
    }
}
