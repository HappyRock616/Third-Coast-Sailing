package com.thirdcoastsailing.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the MapLibre style for the imported lake packages.
 * One vector source per lake (local .pmtiles file); the same set of layers is drawn for each lake.
 * Land is tan/charcoal, water is blue shaded by depth. Water shallower than the boat's minimum depth
 * is shaded red with one red line at that depth; hazards are red.
 */
object ChartStyle {

    /** Colors for one display mode. */
    private data class Palette(
        val background: String, val land: String, val coast: String,
        val water: List<String>,          // shallow -> deep
        val tooShallow: String,           // water shallower than the boat's minimum depth (red-tinted)
        val contour: String, val structure: String,
        val hazard: String, val harbor: String, val buoy: String,
    )

    private val DAY = Palette(
        background = "#D9DCDF", land = "#E9DDBF", coast = "#6E6048",   // light grey where there is no chart
        water = listOf("#E3F0FA", "#CCE2F4", "#B4D3EC", "#98C1E3", "#7DAFD9"),
        tooShallow = "#F29A92",
        contour = "#5B8DB8", structure = "#4A4A4A",
        hazard = "#D62828", harbor = "#F28C28", buoy = "#707070",
    )

    private val NIGHT = Palette(
        background = "#45494F", land = "#2A2D31", coast = "#8A8F96",   // grey where there is no chart
        water = listOf("#2E4B63", "#24405A", "#1C3650", "#152C45", "#10233A"),
        tooShallow = "#5C2E36",
        contour = "#4D7EA8", structure = "#B0B4B8",
        hazard = "#FF4D4D", harbor = "#F28C28", buoy = "#8A8F96",
    )

    // Depth band edges in meters (NOAA charts carry metric depth areas): 6, 18, 30, 60 ft
    private val DEPTH_EDGES_M = listOf(1.8, 5.4, 9.1, 18.2)

    // Margin (m) so an area starting exactly at the limit (7.3 m for 24 ft) isn't shaded through feet/meter rounding
    private const val LIMIT_MARGIN_M = 0.1

    /**
     * @param lakes          imported lakes with their .pmtiles files
     * @param night          dark palette when true
     * @param depthLimitFt   water shallower than this is shaded red (default 24 ft)
     * @param basemap        the rough North America basemap file (Basemap.file), drawn under the charts; null = none
     */
    fun build(lakes: List<Lake>, night: Boolean, depthLimitFt: Double = 24.0, basemap: java.io.File? = null): String {
        val p = if (night) NIGHT else DAY
        val limitM = depthLimitFt * 0.3048
        val sources = JSONObject()
        val layers = JSONArray()

        layers.put(JSONObject().put("id", "background").put("type", "background")
            .put("paint", JSONObject().put("background-color", p.background)))
        if (basemap != null) {
            sources.put(BASEMAP, JSONObject().put("type", "vector").put("url", "pmtiles://file://${basemap.absolutePath}"))
            addBasemap(layers, night)
        }

        for (lake in lakes) {
            val src = "lake_${lake.id}"
            sources.put(src, JSONObject()
                .put("type", "vector")
                .put("url", "pmtiles://file://${lake.pmtiles.absolutePath}"))
        }
        // Water for every lake first, then land and everything on top, so overlays slotted under the
        // land layers (weather color field) show on water only, whichever lakes are loaded
        for (lake in lakes) addWaterLayers(layers, "lake_${lake.id}", p, limitM)
        // Land outside every chart area, right above the water: the weather colour is slotted in under it (see
        // landAnchor), so wind never tints land beyond a coastal chart's 5-mile strip
        if (basemap != null) addBasemapInland(layers, night)
        for (lake in lakes) addLakeLayers(layers, "lake_${lake.id}", p, limitM)
        // Navigation aids and names last, over every lake's land and water (labels never hide under a neighbor lake)
        for (lake in lakes) NavStyle.addLayers(layers, "lake_${lake.id}", night)
        if (basemap != null) addBasemapLabels(layers, night)

        return JSONObject()
            .put("version", 8)
            .put("name", "Third Coast Sailing")
            .put("glyphs", NavStyle.GLYPHS)
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /** Source id of the built-in basemap. */
    private const val BASEMAP = "basemap"

    /** Basemap colors: pale land a step off the grey "no chart" background, faint lines, quiet labels. */
    private class BasemapColors(val land: String, val faded: String, val state: String, val country: String,
                                val label: String, val countryLabel: String, val halo: String)
    // All land is the chart's land colour (DAY.land / NIGHT.land), charted or not (Darren, 2026-11-18: a pale outline
    // with beige only along charted shores was hard to read). faded = land: no fade, so chart edges don't show.
    private val BASEMAP_DAY = BasemapColors("#E9DDBF", "#E9DDBF", "#C9B892", "#B0A07C", "#8C7E62", "#7A6D52", "#E9DDBF")
    private val BASEMAP_NIGHT = BasemapColors("#2A2D31", "#2A2D31", "#43474D", "#50555B", "#80858C", "#8C9198", "#2A2D31")

    /** The rough land fades toward the grey background once zoomed in past what its coarse outline can show. */
    private val BASEMAP_FADE = JSONArray("[\"interpolate\", [\"linear\"], [\"zoom\"], 7, 1, 11, 0.45]")

    /** Land colour by zoom: full at 7, faded at 11 (a colour, not transparency, so it still hides what is under it). */
    private fun landColor(c: BasemapColors) =
        JSONArray("[\"interpolate\", [\"linear\"], [\"zoom\"], 7, \"${c.land}\", 11, \"${c.faded}\"]")

    /** Id of the basemap's inland layer: weather overlays go under it (land outside the charts stays clear). */
    const val INLAND_LAYER = "basemap_inland"

    /**
     * Rough land under every chart (1_Chart_Build/make_basemap.py, Natural Earth).
     * Charted water and land draw over it, so where a chart is installed only the real shoreline shows.
     */
    private fun addBasemap(layers: JSONArray, night: Boolean) {
        val c = if (night) BASEMAP_NIGHT else BASEMAP_DAY
        layers.put(base("basemap_land", "fill", BASEMAP, "land", null).put("paint", JSONObject()
            .put("fill-color", landColor(c))))
    }

    /** The same land, but only outside every chart area, drawn above the charts' water and the weather colour; then the borders. */
    private fun addBasemapInland(layers: JSONArray, night: Boolean) {
        val c = if (night) BASEMAP_NIGHT else BASEMAP_DAY
        layers.put(base(INLAND_LAYER, "fill", BASEMAP, "inland", null).put("paint", JSONObject()
            .put("fill-color", landColor(c))))
        // state/province and country lines last, over the inland fill (a chart's own land still covers them)
        layers.put(base("basemap_state", "line", BASEMAP, "state", 3.0).put("paint", JSONObject()
            .put("line-color", c.state).put("line-opacity", BASEMAP_FADE)
            .put("line-width", JSONArray("[\"interpolate\", [\"linear\"], [\"zoom\"], 3, 0.4, 8, 1.0]"))))
        layers.put(base("basemap_country", "line", BASEMAP, "country", null).put("paint", JSONObject()
            .put("line-color", c.country).put("line-opacity", BASEMAP_FADE)
            .put("line-width", JSONArray("[\"interpolate\", [\"linear\"], [\"zoom\"], 2, 0.6, 8, 1.4]"))))
    }

    /** Country names (far out) and state/province names (abbreviations until zoom 5), gone before chart detail. */
    private fun addBasemapLabels(layers: JSONArray, night: Boolean) {
        val c = if (night) BASEMAP_NIGHT else BASEMAP_DAY
        layers.put(base("basemap_state_label", "symbol", BASEMAP, "label", 3.5).put("maxzoom", 8.5)
            .put("filter", JSONArray("[\"==\", [\"get\", \"kind\"], \"state\"]"))
            .put("layout", JSONObject()
                .put("text-field", JSONArray("[\"step\", [\"zoom\"], [\"get\", \"abbr\"], 5, [\"get\", \"name\"]]"))
                .put("text-font", JSONArray().put("roboto-regular"))
                .put("text-size", JSONArray("[\"interpolate\", [\"linear\"], [\"zoom\"], 3.5, 9, 7, 12]"))
                .put("text-transform", "uppercase").put("text-letter-spacing", 0.12).put("text-max-width", 7)
                .put("text-padding", 4).put("symbol-sort-key", JSONArray("[\"get\", \"rank\"]")))
            .put("paint", JSONObject().put("text-color", c.label).put("text-halo-color", c.halo).put("text-halo-width", 1.0)))
        layers.put(base("basemap_country_label", "symbol", BASEMAP, "label", null).put("maxzoom", 6.0)
            .put("filter", JSONArray("[\"all\", [\"==\", [\"get\", \"kind\"], \"country\"], " +
                "[\"<=\", [\"get\", \"rank\"], [\"+\", [\"zoom\"], 1.5]]]"))
            .put("layout", JSONObject()
                .put("text-field", JSONArray("[\"get\", \"name\"]"))
                .put("text-font", JSONArray().put("roboto-medium"))
                .put("text-size", JSONArray("[\"interpolate\", [\"linear\"], [\"zoom\"], 2, 10, 5, 13]"))
                .put("text-transform", "uppercase").put("text-letter-spacing", 0.25).put("text-max-width", 7)
                .put("text-padding", 4).put("symbol-sort-key", JSONArray("[\"get\", \"rank\"]")))
            .put("paint", JSONObject().put("text-color", c.countryLabel).put("text-halo-color", c.halo).put("text-halo-width", 1.0)))
    }

    /** Depth-shaded water: the approximate-depth grid underneath, charted depth areas on top, shallower than the boat's limit in red. */
    private fun addWaterLayers(layers: JSONArray, src: String, p: Palette, limitM: Double) {
        // Approximate depth (NCEI grid) underneath the charted data; charted depth areas draw on top
        // Water shallower than the minimum depth gets the red-tinted color in the same fill (no overlay layer,
        // so overlapping chart cells can't stack into darker patches or seams)
        layers.put(fill("${src}_bathy", src, "bathy_band", depthColor("dmin", p, limitM), opacity = 0.9))
        layers.put(fill("${src}_depth", src, "depth_area", depthColor("drval1", p, limitM)))
    }

    /** First land layer of the style: map overlays added below it draw on water only. */
    fun landAnchor(lakes: List<Lake>): String? = lakes.firstOrNull()?.let { "lake_${it.id}_land" }

    /** Land, coastline, depth contours (with the boat's limit contour in red), structures and hazards for one lake. */
    private fun addLakeLayers(layers: JSONArray, src: String, p: Palette, limitM: Double) {
        // Water shallower than the boat's minimum depth: red hatching over the weather colour (which sits under the
        // land layers and would otherwise wash the red fill out to a beige-looking peach)
        layers.put(base("${src}_shallow_hatch", "fill", src, "depth_area", null)
            .put("filter", JSONArray("[\"<\", [\"coalesce\", [\"get\", \"drval1\"], 0], ${limitM - LIMIT_MARGIN_M}]"))
            .put("paint", JSONObject().put("fill-pattern", SHALLOW_HATCH).put("fill-antialias", false)))
        layers.put(fill("${src}_land", src, "land", p.land))
        layers.put(line("${src}_coast", src, "coastline", p.coast, width = 0.8))
        layers.put(line("${src}_contour", src, "depth_contour", p.contour, width = 0.5, minzoom = 8.0))
        // One red line: the charted contour at the minimum depth (NOAA contours are drawn from zoom 8)
        layers.put(line("${src}_limit", src, "depth_contour", p.hazard, width = 1.5, minzoom = 8.0,
            filter = JSONArray("[\"all\", [\">=\", [\"coalesce\", [\"get\", \"valdco\"], -1], ${limitM - 0.15}], " +
                "[\"<=\", [\"coalesce\", [\"get\", \"valdco\"], -1], ${limitM + 0.15}]]")))
        layers.put(fill("${src}_structarea", src, "shoreline_construction_area", p.structure, minzoom = 11.0))
        layers.put(line("${src}_struct", src, "shoreline_construction", p.structure, width = 1.0, minzoom = 11.0))
        // Hazards: obstructions, rocks, wrecks
        layers.put(fill("${src}_hazarea", src, "hazard_area", p.hazard, opacity = 0.25))
        layers.put(line("${src}_hazareaedge", src, "hazard_area", p.hazard, width = 1.2))
        layers.put(line("${src}_hazline", src, "hazard_line", p.hazard, width = 1.5))
        layers.put(circle("${src}_hazpt", src, "hazard_point", p.hazard, radius = 3.0, minzoom = 10.0))
        // Harbors (orange anchor tile, tappable; see marinaBitmap). Buoys are a separate live layer colored by their latest report (see Buoys.kt).
        layers.put(JSONObject().put("id", "${src}_harbor").put("type", "symbol").put("source", src).put("source-layer", "harbor")
            .put("minzoom", 8.0).put("layout", JSONObject()
                .put("icon-image", MARINA_ICON).put("icon-allow-overlap", true).put("icon-ignore-placement", true)))
    }

    /** Layer ids that open an info card when tapped. */
    fun tapLayers(lakes: List<Lake>): Array<String> =
        (listOf(BUOY_LAYER) + lakes.flatMap { NavStyle.tapLayers("lake_${it.id}") } + lakes.map { "lake_${it.id}_harbor" })
            .toTypedArray()

    /**
     * Fill color by depth: red-tinted where the area's shallow edge is above the minimum depth,
     * otherwise a step ramp where deeper water gets a darker blue.
     */
    private fun depthColor(field: String, p: Palette, limitM: Double): JSONArray {
        val value = "[\"coalesce\", [\"get\", \"$field\"], 0]"
        val ramp = JSONArray().put("step").put(JSONArray(value)).put(p.water[0])
        DEPTH_EDGES_M.forEachIndexed { i, edge -> ramp.put(edge).put(p.water[i + 1]) }
        return JSONArray().put("case")
            .put(JSONArray("[\"<\", $value, ${limitM - LIMIT_MARGIN_M}]")).put(p.tooShallow)
            .put(ramp)
    }

    /** Common part of every layer: id, type, source, source layer and optional minimum zoom. */
    private fun base(id: String, type: String, src: String, layer: String, minzoom: Double?) =
        JSONObject().put("id", id).put("type", type).put("source", src).put("source-layer", layer).also {
            if (minzoom != null) it.put("minzoom", minzoom)
        }

    /** A fill layer (no antialiasing, so neighboring polygons don't show seams). */
    private fun fill(id: String, src: String, layer: String, color: Any, opacity: Double = 1.0,
                     minzoom: Double? = null) =
        base(id, "fill", src, layer, minzoom).put("paint", JSONObject()
            .put("fill-color", color).put("fill-opacity", opacity).put("fill-antialias", false))

    /** A line layer, optionally filtered. */
    private fun line(id: String, src: String, layer: String, color: String, width: Double,
                     minzoom: Double? = null, filter: JSONArray? = null) =
        base(id, "line", src, layer, minzoom).put("paint", JSONObject()
            .put("line-color", color).put("line-width", width)).also { if (filter != null) it.put("filter", filter) }

    /** A circle layer, with an optional thin outline. */
    private fun circle(id: String, src: String, layer: String, color: String, radius: Double,
                       minzoom: Double? = null, stroke: String? = null) =
        base(id, "circle", src, layer, minzoom).put("paint", JSONObject()
            .put("circle-color", color).put("circle-radius", radius).also {
                if (stroke != null) it.put("circle-stroke-color", stroke).put("circle-stroke-width", 1.0)
            })

    /** Style image name of the marina symbol (added with the style, see marinaBitmap). */
    const val MARINA_ICON = "marina"
    /** Fill pattern for water shallower than the minimum depth: thin red diagonals. */
    const val SHALLOW_HATCH = "shallow_hatch"

    /** The hatching tile: one red diagonal across a transparent square (repeats into continuous lines). */
    fun hatchBitmap(density: Float): android.graphics.Bitmap {
        val s = (9 * density).toInt().coerceAtLeast(12)
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCD62828.toInt(); strokeWidth = 1.3f * density; style = android.graphics.Paint.Style.STROKE
        }
        // the corner pieces join the neighbouring tiles' lines
        c.drawLine(0f, s.toFloat(), s.toFloat(), 0f, p)
        c.drawLine(-s / 2f, s / 2f, s / 2f, -s / 2f, p)
        c.drawLine(s / 2f, s * 1.5f, s * 1.5f, s / 2f, p)
        return b
    }

    /**
     * Marina symbol on the chart: an orange rounded tile with a navy anchor and a dark edge, so marinas never look
     * like buoys (which are round). `density` = screen px per dp; the tile is 20 dp.
     */
    fun marinaBitmap(density: Float): android.graphics.Bitmap {
        val s = (20 * density).toInt().coerceAtLeast(16)
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val k = s / 30f                                              // drawn on a 30 x 30 grid
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = android.graphics.RectF(1.5f * k, 1.5f * k, 28.5f * k, 28.5f * k)
        p.color = 0xFFF28C28.toInt(); p.style = android.graphics.Paint.Style.FILL; c.drawRoundRect(r, 6 * k, 6 * k, p)
        p.color = 0xFF1B1F23.toInt(); p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.6f * k
        c.drawRoundRect(r, 6 * k, 6 * k, p)
        // anchor: ring, shank, stock, curved arms
        p.color = 0xFF0F2742.toInt(); p.strokeWidth = 2.6f * k; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawCircle(15 * k, 7.5f * k, 2.2f * k, p)
        c.drawLine(15 * k, 9.7f * k, 15 * k, 24 * k, p)
        c.drawLine(10.5f * k, 12.5f * k, 19.5f * k, 12.5f * k, p)
        c.drawArc(android.graphics.RectF(7.5f * k, 12 * k, 22.5f * k, 24.5f * k), 20f, 140f, false, p)
        return b
    }
}
