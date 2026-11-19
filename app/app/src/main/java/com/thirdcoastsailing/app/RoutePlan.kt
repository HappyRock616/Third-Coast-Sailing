package com.thirdcoastsailing.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Most route points: the app is for rough passage planning, not detailed routes. */
const val MAX_ROUTE_POINTS = 6
/** Routing: fraction of polar speed actually sailed. */
const val CRUISE_FACTOR = 0.85
/** Routing: time lost per tack or gybe (minutes). */
const val TACK_PENALTY_MIN = 15.0

/**
 * The planned route, kept for the whole app process: "+" drops a pin at the middle of the view, a short hold drags it,
 * "−" removes the last (hold to clear). The first pin is the start (green), the last the finish (flag). The start can
 * be snapped to the boat and then follows it; the pins, the link and the progress along the pins are saved, so they
 * survive the screen going off and the app being closed. A weather route calculation runs here too, so it isn't lost
 * with the screen, and each one has its own cancel flag: a result only shows if it is still for the current pins.
 */
object RoutePlan {
    /** The pins (Compose state). */
    var pins by mutableStateOf<List<GeoPt>>(emptyList())
        private set
    /** The start pin is on the boat and follows it. */
    var startOnBoat by mutableStateOf(false)
        private set
    /** Next pin not yet reached while sailing (a re-route starts from the boat toward it). */
    var pinNext by mutableIntStateOf(1)
        private set
    /** Next point to steer for: an index into the calculated route's corners, or into the pins. */
    var navNext by mutableIntStateOf(1)
        private set
    /** The calculated weather route, or null. */
    var result by mutableStateOf<RouteResult?>(null)
        private set
    /** Route button state: idle, calculating, or showing a result. */
    var calcState by mutableStateOf(CalcState.IDLE)
        private set
    /** A pin is being dragged: saving and the shallow-water check wait until it is let go. */
    var dragging by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null
    private var store: RouteStore? = null
    private var calcJob: Job? = null
    private var calcCancel = AtomicBoolean(false)            // replaced for every calculation

    /** Read the saved route once per process. */
    fun load(context: Context) {
        if (prefs != null) return
        val p = appPrefs(context).also { prefs = it }
        store = RouteStore(p).also { pins = it.load() }
        startOnBoat = p.getBoolean(PrefKeys.ROUTE_ON_BOAT, false)
        pinNext = p.getInt(PrefKeys.ROUTE_PIN_NEXT, 1)
        navNext = pinNext
    }

    private fun saveLink() {
        prefs?.edit()?.putBoolean(PrefKeys.ROUTE_ON_BOAT, startOnBoat)?.putInt(PrefKeys.ROUTE_PIN_NEXT, pinNext)?.apply()
    }

    /** The route as sailed: the start pin replaced by the boat's position while it is snapped to the boat. */
    fun effective(boat: GeoPt?): List<GeoPt> =
        if (startOnBoat && boat != null && pins.isNotEmpty()) listOf(boat) + pins.drop(1) else pins

    /** Points being sailed: the calculated route's corners (tacks, gybes, pins), or else the pins themselves. */
    fun navPoints(boat: GeoPt?): List<GeoPt> = result?.let { routeTurns(it.path, pins) } ?: effective(boat)

    /** New pins: any calculation is now out of date. `save` = false while a pin is dragged (saved when let go). */
    private fun set(p: List<GeoPt>, save: Boolean = true) {
        pins = p
        if (p.isEmpty()) startOnBoat = false
        navNext = 1; pinNext = 1
        if (save) { store?.save(p); saveLink() }
        cancelCalc()
        result = null
        calcState = CalcState.IDLE
    }

    /** "+": the next pin at the middle of the current view (never attached to the boat). */
    fun add(at: GeoPt) {
        if (pins.size >= MAX_ROUTE_POINTS) { Status.show("Up to $MAX_ROUTE_POINTS route points"); return }
        set(pins + at)
    }

    /** "−": remove the last pin. */
    fun removeLast() { if (pins.isNotEmpty()) set(pins.dropLast(1)) }

    /** Hold "−": clear the whole route. */
    fun clear() = set(emptyList())

    /** A pin is being dragged: moving the start pin unhooks it from the boat. */
    fun move(i: Int, p: GeoPt) {
        if (i !in pins.indices) return
        if (i == 0) startOnBoat = false
        dragging = true
        set(pins.toMutableList().also { it[i] = p }, save = false)
    }

    /** The dragged pin was let go: save the route. */
    fun endDrag() {
        if (!dragging) return
        dragging = false
        store?.save(pins); saveLink()
    }

    /** The start pin was let go close to the boat: snap it on (it then follows the boat). */
    fun snapStart(boat: GeoPt) {
        if (pins.isEmpty()) return
        dragging = false
        set(pins.toMutableList().also { it[0] = boat })
        startOnBoat = true
        saveLink()
    }

    /** While sailing with the start on the boat: step past every point within POINT_REACHED_NM of the boat. */
    fun advance(boat: GeoPt) {
        if (!startOnBoat) return
        val nav = navPoints(boat); val eff = effective(boat)
        var n = navNext; var p = pinNext
        while (n < nav.size && distNm(boat, nav[n]) <= POINT_REACHED_NM) n++
        while (p < eff.size && distNm(boat, eff[p]) <= POINT_REACHED_NM) p++
        if (n != navNext) navNext = n
        if (p != pinNext) { pinNext = p; saveLink() }
    }

    private fun cancelCalc() { calcCancel.set(true); calcJob?.cancel(); calcJob = null }

    /**
     * The route button: calculate a weather route (from the boat when the start is snapped to it), cancel one that is
     * running, or clear the result.
     * @param departMs when the boat is at the start (the forecast time on the slider)
     */
    fun onCalc(boat: GeoPt?, polar: Polar?, lakes: List<Lake>, depthLimitFt: Double, departMs: Long, weather: WeatherRepo) {
        when (calcState) {
            CalcState.DONE -> { result = null; calcState = CalcState.IDLE }
            CalcState.WORKING -> { cancelCalc(); calcState = CalcState.IDLE; Status.show("Route calculation stopped") }
            CalcState.IDLE -> {
                polar ?: run { Status.show("Set up a boat first: menu → Boat"); return }
                val eff = effective(boat)
                // on the boat: (re)route from where the boat is now through the pins not yet reached
                val onBoat = startOnBoat && boat != null
                val start = eff.firstOrNull()
                val wps = if (onBoat) eff.drop(pinNext.coerceAtLeast(1)) else eff.drop(1)
                if (start == null || wps.isEmpty()) {
                    Status.show(if (onBoat && eff.size > 1) "Route finished" else "Add a finish point first"); return
                }
                val files = lakes.map { File(it.pmtiles.parentFile, "${it.id}_hazards.gpkg") }
                val cancel = AtomicBoolean(false).also { calcCancel = it }      // this calculation's own flag
                calcState = CalcState.WORKING
                Status.show("Calculating route…")
                calcJob = appScope.launch {
                    val r = withContext(Dispatchers.Default) {
                        runCatching {
                            val all = listOf(start) + wps
                            val mask = NavMask.build(files, Router.boxFor(all), depthLimitFt / FT_PER_M, all) { cancel.get() }
                            Router.route(start, wps, departMs, polar, WindLookup(weather), mask,
                                CRUISE_FACTOR, TACK_PENALTY_MIN) { cancel.get() }
                        }
                    }
                    if (cancel.get() || calcCancel !== cancel) return@launch        // pins changed or stopped meanwhile
                    r.onSuccess {
                        result = it
                        navNext = 1
                        calcState = CalcState.DONE
                        Status.show("Route: %.1f nm sailed, %s".format(it.distanceNm, hoursText((it.endMs - it.startMs) / 3_600_000.0)))
                    }.onFailure {
                        calcState = CalcState.IDLE
                        Status.show(it.message ?: "Route calculation failed")
                    }
                }
            }
        }
    }

    /** Key for the shallow-water check: the route rounded to about 100 m, so a snapped start re-checks as the boat moves. */
    fun checkKey(boat: GeoPt?): List<Pair<Int, Int>> =
        effective(boat).map { (it.lat * 1000).roundToInt() to (it.lon * 1000).roundToInt() }
}
