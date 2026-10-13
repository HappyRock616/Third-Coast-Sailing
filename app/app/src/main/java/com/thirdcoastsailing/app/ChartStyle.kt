package com.thirdcoastsailing.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the MapLibre style for the imported lake packages.
 * One vector source per lake (local .pmtiles file); the same set of layers is drawn for each lake.
 * Land is tan/charcoal, water is blue shaded by depth, anything shallower than the depth limit
 * and every hazard is outlined in red.
 */
object ChartStyle {

    /** Colors for one display mode. */
    private data class Palette(
        val background: String, val land: String, val coast: String,
        val water: List<String>,          // shallow -> deep
        val contour: String, val structure: String,
        val hazard: String, val harbor: String, val buoy: String,
    )

    private val DAY = Palette(
        background = "#E9DDBF", land = "#E9DDBF", coast = "#6E6048",
        water = listOf("#E3F0FA", "#CCE2F4", "#B4D3EC", "#98C1E3", "#7DAFD9"),
        contour = "#5B8DB8", structure = "#4A4A4A",
        hazard = "#D62828", harbor = "#F28C28", buoy = "#707070",
    )

    private val NIGHT = Palette(
        background = "#2A2D31", land = "#2A2D31", coast = "#8A8F96",
        water = listOf("#2E4B63", "#24405A", "#1C3650", "#152C45", "#10233A"),
        contour = "#4D7EA8", structure = "#B0B4B8",
        hazard = "#FF4D4D", harbor = "#F28C28", buoy = "#8A8F96",
    )

    // Depth band edges in meters (NOAA charts carry metric depth areas): 6, 18, 30, 60 ft
    private val DEPTH_EDGES_M = listOf(1.8, 5.4, 9.1, 18.2)

    /**
     * @param lakes          imported lakes with their .pmtiles files
     * @param night          dark palette when true
     * @param depthLimitFt   water shallower than this gets a red outline (default 24 ft)
     */
    fun build(lakes: List<Lake>, night: Boolean, depthLimitFt: Double = 24.0): String {
        val p = if (night) NIGHT else DAY
        val limitM = depthLimitFt * 0.3048
        val sources = JSONObject()
        val layers = JSONArray()

        layers.put(JSONObject().put("id", "background").put("type", "background")
            .put("paint", JSONObject().put("background-color", p.background)))

        for (lake in lakes) {
            val src = "lake_${lake.id}"
            sources.put(src, JSONObject()
                .put("type", "vector")
                .put("url", "pmtiles://file://${lake.pmtiles.absolutePath}"))
        }
        // Water for every lake first, then land and everything on top, so overlays slotted under the
        // land layers (weather colour field) show on water only, whichever lakes are loaded
        for (lake in lakes) addWaterLayers(layers, "lake_${lake.id}", p, limitM)
        for (lake in lakes) addLakeLayers(layers, "lake_${lake.id}", p)

        return JSONObject()
            .put("version", 8)
            .put("name", "Third Coast Sailing")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun addWaterLayers(layers: JSONArray, src: String, p: Palette, limitM: Double) {
        // Approximate depth (NCEI grid) underneath the charted data; charted depth areas draw on top
        layers.put(fill("${src}_bathy", src, "bathy_band", depthColor("dmin", p), opacity = 0.9))
        layers.put(fill("${src}_depth", src, "depth_area", depthColor("drval1", p)))
        // Red outline: charted water shallower than the depth limit
        layers.put(line("${src}_shallow", src, "depth_area", p.hazard, width = 1.0,
            filter = JSONArray("[\"<\", [\"coalesce\", [\"get\", \"drval1\"], 0], $limitM]")))
    }

    /** First land layer of the style: map overlays added below it draw on water only. */
    fun landAnchor(lakes: List<Lake>): String? = lakes.firstOrNull()?.let { "lake_${it.id}_land" }

    private fun addLakeLayers(layers: JSONArray, src: String, p: Palette) {
        layers.put(fill("${src}_land", src, "land", p.land))
        layers.put(line("${src}_coast", src, "coastline", p.coast, width = 0.8))
        layers.put(line("${src}_contour", src, "depth_contour", p.contour, width = 0.5, minzoom = 8.0))
        layers.put(fill("${src}_structarea", src, "shoreline_construction_area", p.structure, minzoom = 11.0))
        layers.put(line("${src}_struct", src, "shoreline_construction", p.structure, width = 1.0, minzoom = 11.0))
        // Hazards: obstructions, rocks, wrecks
        layers.put(fill("${src}_hazarea", src, "hazard_area", p.hazard, opacity = 0.25))
        layers.put(line("${src}_hazareaedge", src, "hazard_area", p.hazard, width = 1.2))
        layers.put(line("${src}_hazline", src, "hazard_line", p.hazard, width = 1.5))
        layers.put(circle("${src}_hazpt", src, "hazard_point", p.hazard, radius = 3.0, minzoom = 10.0))
        // Harbors (orange, tappable). Buoys are a separate live layer coloured by their latest report (see Buoys.kt).
        layers.put(circle("${src}_harbor", src, "harbor", p.harbor, radius = 5.0, minzoom = 8.0, stroke = "#1B1F23"))
    }

    /** Layer ids that open an info card when tapped. */
    fun tapLayers(lakes: List<Lake>): Array<String> =
        (listOf(BUOY_LAYER) + lakes.map { "lake_${it.id}_harbor" }).toTypedArray()

    /** Step expression: deeper water gets a darker blue. */
    private fun depthColor(field: String, p: Palette): JSONArray {
        val e = JSONArray().put("step")
            .put(JSONArray("[\"coalesce\", [\"get\", \"$field\"], 0]"))
            .put(p.water[0])
        DEPTH_EDGES_M.forEachIndexed { i, edge -> e.put(edge).put(p.water[i + 1]) }
        return e
    }

    private fun base(id: String, type: String, src: String, layer: String, minzoom: Double?) =
        JSONObject().put("id", id).put("type", type).put("source", src).put("source-layer", layer).also {
            if (minzoom != null) it.put("minzoom", minzoom)
        }

    private fun fill(id: String, src: String, layer: String, color: Any, opacity: Double = 1.0,
                     minzoom: Double? = null) =
        base(id, "fill", src, layer, minzoom).put("paint", JSONObject()
            .put("fill-color", color).put("fill-opacity", opacity).put("fill-antialias", false))

    private fun line(id: String, src: String, layer: String, color: String, width: Double,
                     minzoom: Double? = null, filter: JSONArray? = null) =
        base(id, "line", src, layer, minzoom).put("paint", JSONObject()
            .put("line-color", color).put("line-width", width)).also { if (filter != null) it.put("filter", filter) }

    private fun circle(id: String, src: String, layer: String, color: String, radius: Double,
                       minzoom: Double? = null, stroke: String? = null) =
        base(id, "circle", src, layer, minzoom).put("paint", JSONObject()
            .put("circle-color", color).put("circle-radius", radius).also {
                if (stroke != null) it.put("circle-stroke-color", stroke).put("circle-stroke-width", 1.0)
            })
}
