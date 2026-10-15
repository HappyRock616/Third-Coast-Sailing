package com.thirdcoastsailing.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thirdcoastsailing.app.celestial.DEFAULT_HEIGHT_OF_EYE_FT
import com.thirdcoastsailing.app.celestial.Field
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.Limb
import com.thirdcoastsailing.app.celestial.Shot
import com.thirdcoastsailing.app.celestial.SightWork
import com.thirdcoastsailing.app.celestial.SkillLevel
import com.thirdcoastsailing.app.celestial.SkyBody
import com.thirdcoastsailing.app.celestial.WorkFix
import com.thirdcoastsailing.app.celestial.WorkLop
import com.thirdcoastsailing.app.celestial.Workbook
import com.thirdcoastsailing.app.celestial.WorkbookSettings
import com.thirdcoastsailing.app.celestial.WorkbookSight
import org.json.JSONArray
import org.json.JSONObject

/*
 * The celestial trainer's saved state: skill level, height of eye, index error, the sights in the workbook and a
 * DR position typed in for when there's no GPS. Kept on the phone (app preferences) so a session survives closing
 * the app; the reset arrow in the workbook clears the sights (and with them the lines and fix on the chart).
 */

data class CelestialState(
    val settings: WorkbookSettings = WorkbookSettings(),
    val sights: List<WorkbookSight> = emptyList(),
    val manualDr: LatLon? = null,
    val nextId: Long = 1,
    val saved: List<SavedFix> = emptyList(),        // earlier fixes this trip, oldest first, each with its workbook
)

/** A fix kept for the trip log: where and when, and the workbook (sights and settings) that produced it. */
data class SavedFix(val timeMs: Long, val position: LatLon, val settings: WorkbookSettings, val sights: List<WorkbookSight>)

/** A point on the celestial fix log drawn on the chart. `saved` = false for the current, not yet saved, fix. */
data class LogFix(val timeMs: Long, val position: LatLon, val saved: Boolean, val radius95Nm: Double? = null)

/**
 * What the workbook works out from the state: each sight's working, the lines of position, the fix, and the fix
 * log (saved fixes, then the current fix if there is one) for the chart.
 */
data class CelestialPlot(val works: List<SightWork>, val lops: List<WorkLop>, val fix: WorkFix?, val log: List<LogFix> = emptyList())

fun celestialPlot(s: CelestialState): CelestialPlot {
    val works = s.sights.map { runCatching { Workbook.work(it, s.settings) }.getOrNull() ?: SightWork(it, null, 0, emptyList(), emptyList(), null) }
    val lops = works.mapNotNull { it.lop }
    val fix = runCatching { Workbook.fix(lops) }.getOrNull()
    val log = s.saved.map { f -> LogFix(f.timeMs, f.position, true, runCatching { savedPlot(f).fix?.uncertainty?.radius95Nm }.getOrNull()) } +
        listOfNotNull(fix?.let { LogFix(it.timeMs, it.position, false, it.uncertainty?.radius95Nm) })
    return CelestialPlot(works, lops, fix, log)
}

/** A saved fix's workbook, worked again for viewing (read-only). */
fun savedPlot(f: SavedFix): CelestialPlot = celestialPlot(CelestialState(settings = f.settings, sights = f.sights))

class CelestialStore(context: Context) {

    private val prefs = context.getSharedPreferences("tcs_celestial", Context.MODE_PRIVATE)

    var state by mutableStateOf(load())
        private set

    fun update(f: (CelestialState) -> CelestialState) {
        state = f(state)
        prefs.edit().putString(KEY, toJson(state).toString()).apply()
    }

    fun updateSight(id: Long, f: (WorkbookSight) -> WorkbookSight) =
        update { st -> st.copy(sights = st.sights.map { if (it.id == id) f(it) else it }) }

    fun addSight(body: SkyBody): Long {
        val id = state.nextId
        update { it.copy(sights = it.sights + WorkbookSight(id, body), nextId = id + 1) }
        return id
    }

    fun removeSight(id: Long) = update { st -> st.copy(sights = st.sights.filter { it.id != id }) }

    /** Keep the current fix and its workbook in the trip log, and start a fresh workbook for the next fix. */
    fun saveFix() = update { st ->
        val fix = celestialPlot(st).fix ?: return@update st
        st.copy(saved = st.saved + SavedFix(fix.timeMs, fix.position, st.settings, st.sights), sights = emptyList())
    }

    /** The reset arrow: clear the current sights, every saved fix and the fix log (settings stay). */
    fun clearAll() = update { it.copy(sights = emptyList(), saved = emptyList()) }

    private fun load(): CelestialState =
        runCatching { prefs.getString(KEY, null)?.let { fromJson(JSONObject(it)) } }.getOrNull() ?: CelestialState()

    private companion object {
        const val KEY = "state"

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
                    put("settings", settingsJson(f.settings)); put("sights", sightsJson(f.sights))
                })
            })
        }

        fun settingsJson(s: WorkbookSettings) = JSONObject().apply {
            put("level", s.level.name); put("hoe", s.heightOfEyeFt); put("ie", s.indexErrorMin); put("tc", s.temperatureC); put("mb", s.pressureMb)
        }

        fun settingsFrom(o: JSONObject) = WorkbookSettings(
            level = runCatching { SkillLevel.valueOf(o.optString("level")) }.getOrDefault(SkillLevel.BEGINNER),
            heightOfEyeFt = o.optDouble("hoe", DEFAULT_HEIGHT_OF_EYE_FT),
            indexErrorMin = o.optDouble("ie", 0.0),
            temperatureC = o.optDouble("tc", 10.0),
            pressureMb = o.optDouble("mb", 1010.0),
        )

        fun sightsJson(list: List<WorkbookSight>) = JSONArray().apply {
            for (w in list) put(JSONObject().apply {
                put("id", w.id); put("body", w.body.name); put("limb", w.limb.name)
                put("shots", JSONArray().apply {
                    for (sh in w.shots) put(JSONArray().put(sh.timeMs)
                        .put(if (sh.hsDeg.isNaN()) JSONObject.NULL else sh.hsDeg)     // JSON can't hold NaN
                        .put(sh.dr.lat).put(sh.dr.lon).put(sh.gps))
                })
                put("entries", JSONObject().apply { for ((f, v) in w.entries) put(f.name, v) })
            })
        }

        fun sightsFrom(arr: JSONArray): List<WorkbookSight> = (0 until arr.length()).mapNotNull { i ->
            val so = arr.getJSONObject(i)
            val body = SkyBody.byName(so.optString("body")) ?: return@mapNotNull null
            val sh = so.optJSONArray("shots") ?: JSONArray()
            val shots = (0 until sh.length()).map { j ->
                val a = sh.getJSONArray(j)
                Shot(a.getLong(0), if (a.isNull(1)) Double.NaN else a.getDouble(1), LatLon(a.getDouble(2), a.getDouble(3)), a.optBoolean(4, true))
            }
            val eo = so.optJSONObject("entries")
            val entries = mutableMapOf<Field, Double>()
            if (eo != null) for (k in eo.keys()) runCatching { entries[Field.valueOf(k)] = eo.getDouble(k) }
            WorkbookSight(so.getLong("id"), body, runCatching { Limb.valueOf(so.optString("limb")) }.getOrDefault(Limb.CENTER), shots, entries)
        }

        fun fromJson(o: JSONObject): CelestialState {
            val set = WorkbookSettings(
                level = runCatching { SkillLevel.valueOf(o.optString("level")) }.getOrDefault(SkillLevel.BEGINNER),
                heightOfEyeFt = o.optDouble("hoe", DEFAULT_HEIGHT_OF_EYE_FT),
                indexErrorMin = o.optDouble("ie", 0.0),
                temperatureC = o.optDouble("tc", 10.0),
                pressureMb = o.optDouble("mb", 1010.0),
            )
            val dr = o.optJSONArray("dr")?.let { LatLon(it.getDouble(0), it.getDouble(1)) }
            val sights = sightsFrom(o.optJSONArray("sights") ?: JSONArray())
            val sa = o.optJSONArray("saved") ?: JSONArray()
            val saved = (0 until sa.length()).mapNotNull { i ->
                runCatching {
                    val f = sa.getJSONObject(i)
                    SavedFix(f.getLong("t"), LatLon(f.getDouble("lat"), f.getDouble("lon")),
                        settingsFrom(f.optJSONObject("settings") ?: JSONObject()), sightsFrom(f.optJSONArray("sights") ?: JSONArray()))
                }.getOrNull()
            }
            val maxId = (sights + saved.flatMap { it.sights }).maxOfOrNull { it.id } ?: 0
            return CelestialState(set, sights, dr, maxOf(o.optLong("nextId", maxId + 1), maxId + 1), saved)
        }
    }
}
