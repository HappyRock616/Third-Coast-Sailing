package com.thirdcoastsailing.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.thirdcoastsailing.app.celestial.DEFAULT_HEIGHT_OF_EYE_FT
import com.thirdcoastsailing.app.celestial.Field
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.Limb
import com.thirdcoastsailing.app.celestial.Shot
import com.thirdcoastsailing.app.celestial.SightCondition
import com.thirdcoastsailing.app.celestial.SightWork
import com.thirdcoastsailing.app.celestial.SkillLevel
import com.thirdcoastsailing.app.celestial.SkyBody
import com.thirdcoastsailing.app.celestial.WorkFix
import com.thirdcoastsailing.app.celestial.WorkLop
import com.thirdcoastsailing.app.celestial.Workbook
import com.thirdcoastsailing.app.celestial.WorkbookSettings
import com.thirdcoastsailing.app.celestial.WorkbookSight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * The celestial trainer's saved state: skill level, height of eye, index error, the sights in the workbook and a
 * DR position typed in for when there's no GPS. Kept on the phone (app preferences) so a session survives closing
 * the app; the reset arrow in the workbook clears the sights (and with them the lines and fix on the chart).
 *
 * The state in memory changes at once; writing it to preferences waits until typing pauses (SAVE_DELAY_MS) and is
 * done off the main thread. The screen calls flush() when it closes or the app goes to the background.
 */

data class CelestialState(
    val settings: WorkbookSettings = WorkbookSettings(),
    val sights: List<WorkbookSight> = emptyList(),
    val manualDr: LatLon? = null,
    val nextId: Long = 1,
    val saved: List<SavedFix> = emptyList(),        // earlier fixes this trip, oldest first, each with its workbook
)

/**
 * A fix kept for the trip log: where and when, the workbook (sights and settings) that produced it, and the radius
 * of its 95% circle (kept so the chart needn't work every saved sight again; null if there was none).
 */
data class SavedFix(
    val timeMs: Long, val position: LatLon, val settings: WorkbookSettings, val sights: List<WorkbookSight>,
    val radius95Nm: Double? = null,
)

/** A point on the celestial fix log drawn on the chart. `saved` = false for the current, not yet saved, fix. */
data class LogFix(val timeMs: Long, val position: LatLon, val saved: Boolean, val radius95Nm: Double? = null)

/**
 * What the workbook works out from the state: each sight's working, the lines of position, the fix, the fix log
 * (saved fixes, then the current fix if there is one) for the chart, and plain-language warnings.
 */
data class CelestialPlot(
    val works: List<SightWork>, val lops: List<WorkLop>, val fix: WorkFix?, val log: List<LogFix> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Everything the chart draws for the celestial trainer: the current workbook's lines of position and fix, plus the
 * trip log of saved fixes. Works every current sight (a few milliseconds each): call it off the main thread
 * ([rememberCelestialPlot]).
 */
fun celestialPlot(s: CelestialState): CelestialPlot {
    val (works, fix) = Workbook.solve(s.sights, s.settings)
    val lops = works.mapNotNull { it.lop }
    val log = s.saved.map { f -> LogFix(f.timeMs, f.position, true, f.radius95Nm) } +
        listOfNotNull(fix?.let { LogFix(it.timeMs, it.position, false, it.uncertainty?.radius95Nm) })
    return CelestialPlot(works, lops, fix, log, Workbook.warnings(works, fix))
}

/** A saved fix's workbook, worked again for viewing (read-only). */
fun savedPlot(f: SavedFix): CelestialPlot = celestialPlot(CelestialState(settings = f.settings, sights = f.sights))

/** The plot for a state, worked out on a background thread; null until the first one is ready (then the last one while the next is worked). */
@Composable
fun rememberCelestialPlot(s: CelestialState): CelestialPlot? {
    val plot by produceState<CelestialPlot?>(null, s) {
        value = withContext(Dispatchers.Default) { runCatching { celestialPlot(s) }.getOrNull() } ?: value
    }
    return plot
}

/** The celestial trainer's saved state (settings, workbook sights, trip log), kept in app preferences and observable by Compose. */
class CelestialStore(context: Context) {

    private val prefs = context.getSharedPreferences("tcs_celestial", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null

    var state by mutableStateOf(load())
        private set

    /** Change the state now; it is written to preferences once changes pause (or on [flush]). */
    fun update(f: (CelestialState) -> CelestialState) {
        state = f(state)
        pending?.cancel()
        pending = scope.launch {
            delay(SAVE_DELAY_MS)
            val snapshot = state
            val text = withContext(Dispatchers.Default) { runCatching { toJson(snapshot).toString() }.getOrNull() }
            if (text != null) prefs.edit().putString(KEY, text).apply()
            pending = null
        }
    }

    /** Write any change not yet saved, straight away (when the screen closes or the app goes to the background). */
    fun flush() {
        if (pending == null) return
        pending?.cancel(); pending = null
        runCatching { toJson(state).toString() }.getOrNull()?.let { prefs.edit().putString(KEY, it).apply() }
    }

    /** Change one workbook sight (matched by id). */
    fun updateSight(id: Long, f: (WorkbookSight) -> WorkbookSight) =
        update { st -> st.copy(sights = st.sights.map { if (it.id == id) f(it) else it }) }

    /** Add a sight of a body to the workbook; returns its id. */
    fun addSight(body: SkyBody): Long {
        val id = state.nextId
        update { it.copy(sights = it.sights + WorkbookSight(id, body), nextId = id + 1) }
        return id
    }

    /** Remove one sight from the workbook. */
    fun removeSight(id: Long) = update { st -> st.copy(sights = st.sights.filter { it.id != id }) }

    /** Keep the current fix and its workbook in the trip log, and start a fresh workbook for the next fix. */
    fun saveFix() = update { st ->
        val fix = celestialPlot(st).fix ?: return@update st
        st.copy(saved = st.saved + SavedFix(fix.timeMs, fix.position, st.settings, st.sights, fix.uncertainty?.radius95Nm),
            sights = emptyList())
    }

    /** The reset arrow: clear the current sights, every saved fix and the fix log (settings stay). */
    fun clearAll() = update { it.copy(sights = emptyList(), saved = emptyList()) }

    /**
     * Read the saved state, or a fresh one if there is none. If it can't be read at all, the text is kept under
     * [CORRUPT_KEY] (so nothing is lost for good) before starting fresh.
     */
    private fun load(): CelestialState {
        val text = prefs.getString(KEY, null) ?: return CelestialState()
        return runCatching { fromJson(JSONObject(text)) }.getOrElse {
            android.util.Log.w("Celestial", "saved state unreadable, kept under $CORRUPT_KEY: $it")
            prefs.edit().putString(CORRUPT_KEY, text).apply()
            CelestialState()
        }
    }

    private companion object {
        const val KEY = "state"
        /** Where unreadable saved state is kept instead of being overwritten. */
        const val CORRUPT_KEY = "celestial_corrupt_backup"
        /** Save this long after the last change (ms). */
        const val SAVE_DELAY_MS = 500L

        fun toJson(s: CelestialState): JSONObject = JSONObject().apply {
            put("level", s.settings.level.name)
            put("hoe", s.settings.heightOfEyeFt)
            put("ie", s.settings.indexErrorMin)
            put("tc", s.settings.temperatureC)
            put("mb", s.settings.pressureMb)
            s.manualDr?.let { put("dr", JSONArray().put(it.lat).put(it.lon)) }
            put("nextId", s.nextId)
            put("sights", sightsJson(s.sights))
            put("saved", JSONArray().apply {
                for (f in s.saved) put(JSONObject().apply {
                    put("t", f.timeMs); put("lat", f.position.lat); put("lon", f.position.lon)
                    f.radius95Nm?.takeIf { it.isFinite() }?.let { put("r95", it) }
                    put("settings", settingsJson(f.settings)); put("sights", sightsJson(f.sights))
                })
            })
        }

        fun settingsJson(s: WorkbookSettings) = JSONObject().apply {
            put("level", s.level.name); put("hoe", s.heightOfEyeFt); put("ie", s.indexErrorMin); put("tc", s.temperatureC); put("mb", s.pressureMb)
        }

        /** A number from JSON, or `def` if it is missing or not finite. */
        fun JSONObject.num(key: String, def: Double): Double = optDouble(key, def).takeIf { it.isFinite() } ?: def

        fun settingsFrom(o: JSONObject) = WorkbookSettings(
            level = runCatching { SkillLevel.valueOf(o.optString("level")) }.getOrDefault(SkillLevel.BEGINNER),
            heightOfEyeFt = o.num("hoe", DEFAULT_HEIGHT_OF_EYE_FT),
            indexErrorMin = o.num("ie", 0.0),
            temperatureC = o.num("tc", 10.0),
            pressureMb = o.num("mb", 1010.0),
        )

        fun sightsJson(list: List<WorkbookSight>) = JSONArray().apply {
            for (w in list) put(JSONObject().apply {
                put("id", w.id); put("bid", w.body.id); put("body", w.body.name); put("limb", w.limb.name); put("cond", w.condition.name)
                put("shots", JSONArray().apply {
                    for (sh in w.shots) put(JSONArray().put(sh.timeMs)
                        .put(if (sh.hsDeg.isFinite()) sh.hsDeg else JSONObject.NULL)     // JSON can't hold NaN
                        .put(sh.dr.lat).put(sh.dr.lon).put(sh.gps))
                })
                put("entries", JSONObject().apply { for ((f, v) in w.entries) if (v.isFinite()) put(f.name, v) })
            })
        }

        /** The sights in a JSON array; one that can't be read is skipped (and logged), not the whole list. */
        fun sightsFrom(arr: JSONArray): List<WorkbookSight> = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val so = arr.getJSONObject(i)
                // stable id first (newer saves), then the name (older saves)
                val body = so.optString("bid").takeIf { it.isNotEmpty() }?.let { SkyBody.byId(it) }
                    ?: SkyBody.byName(so.optString("body")) ?: return@runCatching null
                val sh = so.optJSONArray("shots") ?: JSONArray()
                val shots = (0 until sh.length()).mapNotNull { j ->
                    runCatching {
                        val a = sh.getJSONArray(j)
                        Shot(a.getLong(0), if (a.isNull(1)) Double.NaN else a.getDouble(1), LatLon(a.getDouble(2), a.getDouble(3)), a.optBoolean(4, true))
                    }.getOrNull()
                }
                val eo = so.optJSONObject("entries")
                val entries = mutableMapOf<Field, Double>()
                if (eo != null) for (k in eo.keys()) runCatching { entries[Field.valueOf(k)] = eo.getDouble(k) }
                WorkbookSight(so.getLong("id"), body, runCatching { Limb.valueOf(so.optString("limb")) }.getOrDefault(Limb.CENTER), shots, entries,
                    runCatching { SightCondition.valueOf(so.optString("cond")) }.getOrDefault(SightCondition.MODERATE))
            }.onFailure { android.util.Log.w("Celestial", "skipped a saved sight: $it") }.getOrNull()
        }

        fun fromJson(o: JSONObject): CelestialState {
            val set = settingsFrom(o)
            val dr = runCatching { o.optJSONArray("dr")?.let { LatLon(it.getDouble(0), it.getDouble(1)) } }.getOrNull()
                ?.takeIf { it.lat.isFinite() && it.lon.isFinite() && kotlin.math.abs(it.lat) <= 90.0 && kotlin.math.abs(it.lon) <= 180.0 }
            val sights = sightsFrom(o.optJSONArray("sights") ?: JSONArray())
            val sa = o.optJSONArray("saved") ?: JSONArray()
            val saved = (0 until sa.length()).mapNotNull { i ->
                runCatching {
                    val f = sa.getJSONObject(i)
                    val fix = SavedFix(f.getLong("t"), LatLon(f.getDouble("lat"), f.getDouble("lon")),
                        settingsFrom(f.optJSONObject("settings") ?: JSONObject()), sightsFrom(f.optJSONArray("sights") ?: JSONArray()),
                        f.optDouble("r95", Double.NaN).takeIf { it.isFinite() })
                    // older saves have no radius: work it out once here
                    if (f.has("r95")) fix
                    else fix.copy(radius95Nm = runCatching { savedPlot(fix).fix?.uncertainty?.radius95Nm }.getOrNull())
                }.onFailure { android.util.Log.w("Celestial", "skipped a saved fix: $it") }.getOrNull()
            }
            val maxId = (sights + saved.flatMap { it.sights }).maxOfOrNull { it.id } ?: 0
            return CelestialState(set, sights, dr, maxOf(o.optLong("nextId", maxId + 1), maxId + 1), saved)
        }
    }
}
