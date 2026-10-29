package com.thirdcoastsailing.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Minimum-depth choices (ft): the depth steps NOAA Great Lakes charts carry, so the shading matches real chart data. */
val MIN_DEPTH_CHOICES_FT = listOf(6.0, 12.0, 18.0, 24.0, 30.0)
/** Default minimum water depth in feet for a new boat: routing avoids, and the chart shades red, anything shallower. */
const val DEFAULT_MIN_DEPTH_FT = 24.0

/** A saved boat: which template it starts from, sail options, and the owner's own measurements. */
data class Boat(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val draftFt: Double = 4.9,
    val minDepthFt: Double = DEFAULT_MIN_DEPTH_FT,     // routing limit and red shading on the chart
    val template: String = PolarTemplates.DEFAULT,
    val symSpinnaker: Boolean = false,
    val asymSpinnaker: Boolean = false,
    val manual: List<ManualPoint> = emptyList(),
)

/**
 * Boats are kept in files/boats.json. Each boat's working polar is also written as files/polars/<name>.pol
 * (standard format) so it can be exported and used in other software.
 */
class BoatStore(private val context: Context) {

    private val file = File(context.filesDir, "boats.json")
    private val polDir = File(context.filesDir, "polars").apply { mkdirs() }

    /** Everything saved: the boats and the id of the one in use (null when none is chosen). */
    data class State(val boats: List<Boat>, val activeId: String?)

    /** Read the saved boats; on first run create and save one boat from the default template. */
    fun load(): State {
        if (!file.exists()) {
            // First run: one boat from the default template
            val b = Boat(name = "My boat")
            save(State(listOf(b), b.id))
            return State(listOf(b), b.id)
        }
        val root = JSONObject(file.readText())
        val boats = root.getJSONArray("boats").let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        return State(boats, root.optString("active").ifEmpty { boats.firstOrNull()?.id })
    }

    /** Save all boats (written to a temporary file first, then renamed, so a crash never leaves half a file). */
    fun save(state: State) {
        val a = JSONArray()
        state.boats.forEach { a.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("boats", a).put("active", state.activeId ?: "").toString(1))
        tmp.renameTo(file)
        state.boats.forEach { b -> runCatching { File(polDir, safeName(b.name) + ".pol").writeText(polar(b).toPol()) } }
    }

    /** The polar the router will use: template (+ enabled spinnakers), refit to the owner's measurements. */
    fun polar(b: Boat): Polar =
        PolarMath.refit(PolarTemplates.load(context, b.template, b.symSpinnaker, b.asymSpinnaker), b.manual)

    /** File-safe version of a boat name (letters, digits, '.', '_' and '-' only; "boat" if nothing is left). */
    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifEmpty { "boat" }

    /** One boat as JSON, for the saved file. */
    private fun toJson(b: Boat) = JSONObject()
        .put("id", b.id).put("name", b.name).put("draftFt", b.draftFt).put("minDepthFt", b.minDepthFt).put("template", b.template)
        .put("sym", b.symSpinnaker).put("asym", b.asymSpinnaker)
        .put("manual", JSONArray().also { a ->
            b.manual.forEach { m -> a.put(JSONObject().put("aws", m.awsKn).put("awa", m.awaDeg).put("bsp", m.bspKn)) }
        })

    /** One boat from its saved JSON; missing fields fall back to the defaults. */
    private fun fromJson(o: JSONObject) = Boat(
        id = o.getString("id"), name = o.getString("name"), draftFt = o.optDouble("draftFt", 4.9),
        minDepthFt = o.optDouble("minDepthFt", DEFAULT_MIN_DEPTH_FT),
        template = o.optString("template", PolarTemplates.DEFAULT),
        symSpinnaker = o.optBoolean("sym"), asymSpinnaker = o.optBoolean("asym"),
        manual = o.optJSONArray("manual")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { ManualPoint(it.getDouble("aws"), it.getDouble("awa"), it.getDouble("bsp")) }
        } ?: emptyList(),
    )
}
