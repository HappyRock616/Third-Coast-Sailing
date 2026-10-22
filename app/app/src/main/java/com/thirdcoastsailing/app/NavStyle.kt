package com.thirdcoastsailing.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/*
 * Chart layers for the navigation aids and names in the chart files (chart build 1.4+): place, light, aid, landmark
 * and course. What shows at each zoom (fixed table, most important first):
 *   9+   town names; major lights (range 10 nm or more) as flares
 *   10+  lights with range 5 nm or more; the major light's pattern; conspicuous landmarks; course lines; lake names
 *   12+  every light with its short pattern; buoys and beacons; CG stations; park names; lighthouse names
 *   13+  buoy and beacon numbers (animated lights: see NavLightGlow)
 *   14+  full light text (height and range); fog signals; flagpoles and minor landmarks
 * Labels that would overlap are dropped by the map engine; layers added later win, so the most important labels
 * are added last. Symbols are drawn in code (navImages); none of them is round, so they can't be mistaken for the
 * round weather-buoy and hazard dots.
 */
object NavStyle {
    /** Offline label font: Roboto glyph files bundled in assets/glyphs/<font>/<range>.pbf */
    const val GLYPHS = "asset://glyphs/{fontstack}/{range}.pbf"
    private const val REGULAR = "roboto-regular"
    private const val MEDIUM = "roboto-medium"
    private const val ITALIC = "roboto-italic"

    private data class Ink(val text: String, val halo: String, val water: String, val land: String, val magenta: String)
    private val DAY = Ink("#1B1F23", "#FFFFFF", "#2C5A86", "#6E6048", "#B0207E")
    private val NIGHT = Ink("#E6E8EA", "#1B1F23", "#8FB7DA", "#B0B4B8", "#E05AB8")

    /** Light layer ids of one lake source (tap targets and the glow overlay). */
    fun lightLayers(src: String) = listOf("${src}_light1", "${src}_light2", "${src}_light3")

    /** Tap targets in priority order: lights, then buoys and beacons, then landmarks and CG stations. */
    fun tapLayers(src: String) = lightLayers(src) + listOf("${src}_aid", "${src}_lmk1", "${src}_lmk2", "${src}_cg")

    fun addLayers(layers: JSONArray, src: String, night: Boolean) {
        val k = if (night) NIGHT else DAY
        // course lines: magenta dashed, bearing along the line
        layers.put(base("${src}_course", "line", src, "course", 10.0).put("paint", JSONObject()
            .put("line-color", k.magenta).put("line-width", 1.2).put("line-dasharray", JSONArray("[5, 3]"))))
        layers.put(base("${src}_coursetxt", "symbol", src, "course", 10.0).put("layout", JSONObject()
            .put("symbol-placement", "line").put("text-field", JSONArray("[\"coalesce\", [\"get\", \"bearing\"], \"\"]"))
            .put("text-font", JSONArray().put(REGULAR)).put("text-size", 11).put("text-offset", JSONArray("[0, -0.8]"))
            .put("symbol-spacing", 400)).put("paint", textPaint(k.magenta, k.halo)))

        // landmarks: ring with dot (conspicuous from zoom 10, minor ones from 14); CG badge from 12
        layers.put(base("${src}_lmk1", "symbol", src, "landmark", 10.0)
            .put("filter", JSONArray("[\"all\", [\"==\", [\"get\", \"tier\"], 1], [\"!=\", [\"get\", \"kind\"], \"cg\"]]"))
            .put("layout", iconText("nav_lmk", "[\"get\", \"word\"]", REGULAR, 10.0, "left", 0.9)).put("paint", textPaint(k.text, k.halo)))
        layers.put(base("${src}_lmk2", "symbol", src, "landmark", 14.0)
            .put("filter", JSONArray("[\"all\", [\"!=\", [\"get\", \"tier\"], 1], [\"!=\", [\"get\", \"kind\"], \"cg\"]]"))
            .put("layout", iconText("nav_lmk", "[\"get\", \"word\"]", REGULAR, 10.0, "left", 0.9)).put("paint", textPaint(k.text, k.halo)))
        layers.put(base("${src}_cg", "symbol", src, "landmark", 12.0)
            .put("filter", JSONArray("[\"==\", [\"get\", \"kind\"], \"cg\"]"))
            .put("layout", JSONObject().put("icon-image", "nav_cg").put("icon-allow-overlap", true)))

        // buoys and beacons from zoom 12; numbers from 13
        layers.put(base("${src}_aid", "symbol", src, "aid", 12.0).put("layout", JSONObject()
            .put("icon-image", JSONArray(AID_ICON)).put("icon-allow-overlap", true).put("icon-ignore-placement", true)
            .put("icon-anchor", "bottom")))
        layers.put(base("${src}_aidnum", "symbol", src, "aid", 13.0)
            .put("filter", JSONArray("[\"has\", \"number\"]"))
            .put("layout", JSONObject().put("text-field", JSONArray("[\"concat\", \"\\\"\", [\"get\", \"number\"], \"\\\"\"]"))
                .put("text-font", JSONArray().put(REGULAR)).put("text-size", 11)
                .put("text-variable-anchor", JSONArray("[\"bottom-left\", \"bottom-right\", \"left\", \"right\"]"))
                .put("text-radial-offset", 0.9).put("text-optional", true))
            .put("paint", textPaint(k.text, k.halo)))

        // lights: flare in the light's colour, at the light's position; major lights larger with a tower ring
        layers.put(base("${src}_lhring", "symbol", src, "light", 9.0)
            .put("filter", JSONArray("[\"==\", [\"get\", \"kind\"], \"lighthouse\"]"))
            .put("layout", JSONObject().put("icon-image", "nav_lmk").put("icon-allow-overlap", true).put("icon-ignore-placement", true)))
        for ((tier, minZoom) in listOf(3 to 12.0, 2 to 10.0, 1 to 9.0)) {
            layers.put(base("${src}_light$tier", "symbol", src, "light", minZoom)
                .put("filter", JSONArray("[\"==\", [\"get\", \"tier\"], $tier]"))
                .put("layout", JSONObject().put("icon-image", JSONArray(LIGHT_ICON)).put("icon-size", if (tier == 1) 1.3 else 1.0)
                    .put("icon-allow-overlap", true).put("icon-ignore-placement", true)))
        }
        // fog signals from zoom 14: magenta arcs under the light, "HORN (MRASS)"
        layers.put(base("${src}_fog", "symbol", src, "light", 14.0)
            .put("filter", JSONArray("[\"has\", \"fog_kind\"]"))
            .put("layout", JSONObject().put("icon-image", "nav_fog").put("icon-allow-overlap", true).put("icon-ignore-placement", true)
                .put("icon-offset", JSONArray("[-6, 10]"))
                .put("text-field", JSONArray("[\"case\", [\"has\", \"fog_note\"], [\"concat\", [\"get\", \"fog_kind\"], \" (\", [\"get\", \"fog_note\"], \")\"], [\"get\", \"fog_kind\"]]"))
                .put("text-font", JSONArray().put(REGULAR)).put("text-size", 10)
                .put("text-variable-anchor", JSONArray("[\"top-right\", \"top-left\", \"top\"]")).put("text-radial-offset", 1.6)
                .put("text-optional", true))
            .put("paint", textPaint(k.magenta, k.halo)))

        // names: parks from 12, water from 10, towns from 9 (added last of the names so they win)
        layers.put(place(src, "land", 12.0, ITALIC, 11.0, k.land, k.halo))
        layers.put(place(src, "water", 10.0, ITALIC, 12.0, k.water, k.halo))
        layers.put(place(src, "town", 9.0, MEDIUM, 13.0, k.text, k.halo))

        // light labels: short pattern, full text (height, range) from 14; lighthouse names from 12; major lights win
        val lightText = "[\"step\", [\"zoom\"], [\"get\", \"label_short\"], 14, [\"get\", \"label_full\"]]"
        layers.put(base("${src}_lighttxt", "symbol", src, "light", 12.0)
            .put("filter", JSONArray("[\"!=\", [\"get\", \"tier\"], 1]"))
            .put("layout", varText(lightText, REGULAR, 10.5)).put("paint", textPaint(k.text, k.halo)))
        layers.put(base("${src}_lhname", "symbol", src, "light", 12.0)
            .put("filter", JSONArray("[\"all\", [\"==\", [\"get\", \"kind\"], \"lighthouse\"], [\"has\", \"name\"]]"))
            .put("layout", JSONObject().put("text-field", JSONArray("[\"get\", \"name\"]")).put("text-font", JSONArray().put(ITALIC))
                .put("text-size", 10).put("text-anchor", "top").put("text-offset", JSONArray("[0, 1.6]")).put("text-optional", true)
                .put("text-max-width", 12))
            .put("paint", textPaint(k.text, k.halo)))
        layers.put(base("${src}_lighttxt1", "symbol", src, "light", 10.0)
            .put("filter", JSONArray("[\"==\", [\"get\", \"tier\"], 1]"))
            .put("layout", varText(lightText, MEDIUM, 11.0)).put("paint", textPaint(k.text, k.halo)))
    }

    // icon by light colours: single colour flare, or a split flare for two-colour lights
    private const val LIGHT_ICON = "[\"match\", [\"get\", \"colours\"], " +
        "\"3\", \"nav_flare_3\", \"4\", \"nav_flare_4\", \"6\", \"nav_flare_6\", " +
        "[\"1,3\", \"3,1\"], \"nav_flare_1_3\", [\"1,4\", \"4,1\"], \"nav_flare_1_4\", [\"3,4\", \"4,3\"], \"nav_flare_3_4\", " +
        "\"nav_flare_1\"]"
    // icon by kind and colour: red triangle, green square (on a post for beacons), yellow or white diamond
    private const val AID_ICON = "[\"case\", " +
        "[\"all\", [\"==\", [\"get\", \"kind\"], \"beacon\"], [\"==\", [\"get\", \"colour\"], \"3\"]], \"nav_bcn_3\", " +
        "[\"all\", [\"==\", [\"get\", \"kind\"], \"beacon\"], [\"==\", [\"get\", \"colour\"], \"4\"]], \"nav_bcn_4\", " +
        "[\"==\", [\"get\", \"colour\"], \"3\"], \"nav_nun_3\", [\"==\", [\"get\", \"colour\"], \"4\"], \"nav_can_4\", " +
        "[\"==\", [\"get\", \"colour\"], \"6\"], \"nav_dia_6\", \"nav_dia_1\"]"

    private fun base(id: String, type: String, src: String, layer: String, minZoom: Double) =
        JSONObject().put("id", id).put("type", type).put("source", src).put("source-layer", layer).put("minzoom", minZoom)

    private fun textPaint(color: String, halo: String) = JSONObject()
        .put("text-color", color).put("text-halo-color", halo).put("text-halo-width", 1.4)

    private fun iconText(icon: String, text: String, font: String, size: Double, anchor: String, offset: Double) = JSONObject()
        .put("icon-image", icon).put("icon-allow-overlap", true).put("icon-ignore-placement", true)
        .put("text-field", JSONArray(text)).put("text-font", JSONArray().put(font)).put("text-size", size)
        .put("text-variable-anchor", JSONArray("[\"$anchor\", \"right\", \"top\", \"bottom\"]")).put("text-radial-offset", offset)
        .put("text-optional", true)

    private fun varText(text: String, font: String, size: Double) = JSONObject()
        .put("text-field", JSONArray(text)).put("text-font", JSONArray().put(font)).put("text-size", size)
        .put("text-variable-anchor", JSONArray("[\"left\", \"right\", \"top-left\", \"bottom-left\", \"top\", \"bottom\"]"))
        .put("text-radial-offset", 1.1).put("text-optional", true)

    private fun place(src: String, kind: String, minZoom: Double, font: String, size: Double, color: String, halo: String) =
        base("${src}_place_$kind", "symbol", src, "place", minZoom)
            .put("filter", JSONArray("[\"==\", [\"get\", \"kind\"], \"$kind\"]"))
            .put("layout", JSONObject().put("text-field", JSONArray("[\"get\", \"name\"]")).put("text-font", JSONArray().put(font))
                .put("text-size", size).put("text-max-width", 8).put("symbol-sort-key", 0))
            .put("paint", textPaint(color, halo))

    // ---------------------------------------------------------------- symbols (drawn in code)

    private const val INK = 0xFF1B1F23.toInt()
    private val COLOURS = mapOf("1" to 0xFFF5C400.toInt(), "3" to 0xFFD62828.toInt(), "4" to 0xFF1F9D3A.toInt(),
        "6" to 0xFFF5C400.toInt())

    /** Every navigation symbol by style image name; `density` = screen px per dp. */
    fun navImages(density: Float): Map<String, Bitmap> {
        val out = HashMap<String, Bitmap>()
        for (c in listOf("1", "3", "4", "6")) out["nav_flare_$c"] = flare(density, COLOURS.getValue(c), null)
        for ((a, b) in listOf("1" to "3", "1" to "4", "3" to "4"))
            out["nav_flare_${a}_$b"] = flare(density, COLOURS.getValue(a), COLOURS.getValue(b))
        out["nav_nun_3"] = shape(density, COLOURS.getValue("3"), "tri", post = false)
        out["nav_can_4"] = shape(density, COLOURS.getValue("4"), "square", post = false)
        out["nav_bcn_3"] = shape(density, COLOURS.getValue("3"), "tri", post = true)
        out["nav_bcn_4"] = shape(density, COLOURS.getValue("4"), "square", post = true)
        out["nav_dia_6"] = shape(density, COLOURS.getValue("6"), "diamond", post = false)
        out["nav_dia_1"] = shape(density, 0xFFFFFFFF.toInt(), "diamond", post = false)
        out["nav_lmk"] = ringDot(density)
        out["nav_fog"] = fogArcs(density)
        out["nav_cg"] = cgBadge(density)
        return out
    }

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Light flare: a teardrop from the light's position toward the lower right (chart convention, 135°), so the
     * bitmap's center is the light. Two colours split the flare lengthwise.
     */
    private fun flare(d: Float, c1: Int, c2: Int?): Bitmap {
        val len = 15f * d; val w = 3.4f * d
        val s = (2 * (len + w) + 2 * d).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val cx = s / 2f; val cy = s / 2f
        val a = Math.toRadians(45.0)                                  // down-right on screen
        val tx = cos(a).toFloat(); val ty = sin(a).toFloat(); val nx = -ty; val ny = tx
        val ex = cx + tx * len; val ey = cy + ty * len
        fun arcPt(t: Double) = Pair(ex + w * (cos(t).toFloat() * tx + sin(t).toFloat() * nx), ey + w * (cos(t).toFloat() * ty + sin(t).toFloat() * ny))
        fun half(from: Double, to: Double): Path = Path().apply {
            moveTo(cx, cy)
            for (i in 0..12) { val (x, y) = arcPt(from + (to - from) * i / 12); lineTo(x, y) }
            close()
        }
        val p = paint()
        if (c2 == null) { p.color = c1; cv.drawPath(half(-Math.PI / 2, Math.PI / 2), p) }
        else {
            p.color = c1; cv.drawPath(half(0.0, Math.PI / 2), p)
            p.color = c2; cv.drawPath(half(-Math.PI / 2, 0.0), p)
        }
        p.color = INK; p.style = Paint.Style.STROKE; p.strokeWidth = 0.7f * d
        cv.drawPath(half(-Math.PI / 2, Math.PI / 2), p)
        return b
    }

    /** Buoy or beacon: red triangle, green square, diamond; a beacon stands on a short post. The point is at the bottom. */
    private fun shape(d: Float, color: Int, kind: String, post: Boolean): Bitmap {
        val wd = (14 * d).toInt(); val ht = ((if (post) 20 else 14) * d).toInt()
        val b = Bitmap.createBitmap(wd, ht, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val p = paint(); val cx = wd / 2f
        val top = 1.5f * d; val bot = 12.5f * d                      // the symbol's own box (above the post)
        val path = when (kind) {
            "tri" -> Path().apply { moveTo(cx, top); lineTo(cx + 5.5f * d, bot); lineTo(cx - 5.5f * d, bot); close() }
            "square" -> Path().apply { addRect(cx - 5f * d, top + 1.5f * d, cx + 5f * d, bot, Path.Direction.CW) }
            else -> Path().apply { moveTo(cx, top); lineTo(cx + 5.5f * d, (top + bot) / 2); lineTo(cx, bot); lineTo(cx - 5.5f * d, (top + bot) / 2); close() }
        }
        if (post) { p.color = INK; p.strokeWidth = 1.4f * d; cv.drawLine(cx, bot, cx, ht.toFloat(), p) }
        p.style = Paint.Style.FILL; p.color = color; cv.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 0.8f * d; cv.drawPath(path, p)
        return b
    }

    /** Landmark: white ring with a dark dot (also the tower ring under lighthouse flares). */
    private fun ringDot(d: Float): Bitmap {
        val s = (12 * d).toInt(); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val p = paint(); val c = s / 2f
        p.color = 0xFFFFFFFF.toInt(); cv.drawCircle(c, c, 4.6f * d, p)
        p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 1.1f * d; cv.drawCircle(c, c, 4.6f * d, p)
        p.style = Paint.Style.FILL; cv.drawCircle(c, c, 1.4f * d, p)
        return b
    }

    /** Fog signal: three magenta arcs. */
    private fun fogArcs(d: Float): Bitmap {
        val s = (30 * d).toInt(); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val p = paint(); p.style = Paint.Style.STROKE; p.color = 0xFFB0207E.toInt(); p.strokeWidth = 1.5f * d
        p.strokeCap = Paint.Cap.ROUND
        val c = s / 2f
        for (r in listOf(5f, 9f, 13f)) cv.drawArc(RectF(c - r * d, c - r * d, c + r * d, c + r * d), 30f, 120f, false, p)
        return b
    }

    /** Coast Guard station: navy badge with "CG". */
    private fun cgBadge(d: Float): Bitmap {
        val wd = (24 * d).toInt(); val ht = (15 * d).toInt()
        val b = Bitmap.createBitmap(wd, ht, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val p = paint(); val r = RectF(0.8f * d, 0.8f * d, wd - 0.8f * d, ht - 0.8f * d)
        p.color = 0xFF14233D.toInt(); cv.drawRoundRect(r, 3 * d, 3 * d, p)
        p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 0.9f * d; cv.drawRoundRect(r, 3 * d, 3 * d, p)
        p.style = Paint.Style.FILL; p.textSize = 8.5f * d; p.textAlign = Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        cv.drawText("CG", wd / 2f, ht / 2f + 3f * d, p)
        return b
    }
}
