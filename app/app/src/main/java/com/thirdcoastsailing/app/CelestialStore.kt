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
)

/** What the workbook works out from the state: each sight's working, the lines of position and the fix. */
data class CelestialPlot(val works: List<SightWork>, val lops: List<WorkLop>, val fix: WorkFix?)

fun celestialPlot(s: CelestialState): CelestialPlot {
    val works = s.sights.map { runCatching { Workbook.work(it, s.settings) }.getOrNull() ?: SightWork(it, null, 0, emptyList(), emptyList(), null) }
    val lops = works.mapNotNull { it.lop }
    return CelestialPlot(works, lops, runCatching { Workbook.fix(lops) }.getOrNull())
}

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

    /** The reset arrow: clear every sight (settings stay). */
    fun clearSights() = update { it.copy(sights = emptyList()) }

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
            put("sights", JSONArray().apply {
                for (w in s.sights) put(JSONObject().apply {
                    put("id", w.id); put("body", w.body.name); put("limb", w.limb.name)
                    put("shots", JSONArray().apply {
                        for (sh in w.shots) put(JSONArray().put(sh.timeMs)
                            .put(if (sh.hsDeg.isNaN()) JSONObject.NULL else sh.hsDeg)     // JSON can't hold NaN
                            .put(sh.dr.lat).put(sh.dr.lon))
                    })
                    put("entries", JSONObject().apply { for ((f, v) in w.entries) put(f.name, v) })
                })
            })
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
            val arr = o.optJSONArray("sights") ?: JSONArray()
            val sights = (0 until arr.length()).mapNotNull { i ->
                val so = arr.getJSONObject(i)
                val body = SkyBody.byName(so.optString("body")) ?: return@mapNotNull null
                val sh = so.optJSONArray("shots") ?: JSONArray()
                val shots = (0 until sh.length()).map { j ->
                    val a = sh.getJSONArray(j)
                    Shot(a.getLong(0), if (a.isNull(1)) Double.NaN else a.getDouble(1), LatLon(a.getDouble(2), a.getDouble(3)))
                }
                val eo = so.optJSONObject("entries")
                val entries = mutableMapOf<Field, Double>()
                if (eo != null) for (k in eo.keys()) runCatching { entries[Field.valueOf(k)] = eo.getDouble(k) }
                WorkbookSight(so.getLong("id"), body, runCatching { Limb.valueOf(so.optString("limb")) }.getOrDefault(Limb.CENTER), shots, entries)
            }
            return CelestialState(set, sights, dr, o.optLong("nextId", (sights.maxOfOrNull { it.id } ?: 0) + 1))
        }
    }
}
