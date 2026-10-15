package com.thirdcoastsailing.app.celestial

/*
 * The trainer's three skill levels and the sextant how-to.
 *
 *  BEGINNER      every step: what to shoot and when, where to point, how to use the sextant (with a picture
 *                per step), then the full worked reduction and the fix on the chart.
 *  INTERMEDIATE  suggested targets and times only (preset altitude and bearing); no step-by-step.
 *  ADVANCED      no suggestions: almanac tables, correction tables and the calculator; fix plotted on the chart.
 *
 * Every level gets the reduction and the fix; only the help around it changes. Sextant procedure follows
 * Bowditch, The American Practical Navigator (2019), chapter 14 "Instruments for Celestial Navigation".
 */

enum class SkillLevel(val title: String, val description: String) {
    BEGINNER("Beginner", "Step-by-step: what to shoot, when, where to look and how to use the sextant"),
    INTERMEDIATE("Intermediate", "Suggested bodies and times; you do the rest"),
    ADVANCED("Advanced", "Tables and calculator only; fix plotted on the chart"),
}

/**
 * One step of using the sextant. `graphic` is the drawable resource name of its picture; `graphic2` an optional
 * second picture (a close-up) shown beside it. Paragraphs in `text` are separated by blank lines.
 */
data class SextantStep(val id: String, val title: String, val text: String, val graphic: String, val graphic2: String? = null)

object SextantProcedure {

    val indexError = SextantStep(
        "index_error", "Check index error",
        "Set the sextant to 0°00'. Look at the horizon and turn the micrometer drum until the reflected horizon " +
            "and the direct horizon make one straight line. The reading is the index error: \"on the arc\" if " +
            "above zero, \"off the arc\" if below. Enter it in the app; it's applied to every sight.",
        "sextant_step_index_error",
    )
    val shades = SextantStep(
        "shades", "Sun shades first",
        "Before you look toward the Sun, swing dark shade glasses in front of BOTH mirrors. Never look at the Sun " +
            "through the telescope without them.",
        "sextant_step_shades",
    )
    val preset = SextantStep(
        "preset", "Preset and face the body",
        "Set the sextant to the altitude the app gives, then face the bearing it gives. Look at the horizon " +
            "below that bearing; the body should appear in the mirror half of the horizon glass with a little searching.",
        "sextant_step_preset",
    )
    val bringDown = SextantStep(
        "bring_down", "Bring it down to the horizon",
        "Hold the sextant upright and look at the horizon through the clear half of the horizon glass. Move the " +
            "index arm until the body's reflection sits right on the horizon, then fine-tune with the drum.",
        "sextant_step_bring_down",
    )
    val rock = SextantStep(
        "rock", "Rock the sextant",
        "Tilt the sextant gently left and right around the line of sight (\"swinging the arc\"). The body swings " +
            "in a small arc; the bottom of that arc is where the sextant is exactly upright. Take the sight there.",
        "sextant_step_rock",
    )
    val touchSun = SextantStep(
        "touch_sun", "Lower limb on the horizon",
        "Turn the drum until the bottom edge (lower limb) of the Sun just kisses the horizon at the bottom of the " +
            "swing. Beginners usually bring it down too far: it should touch, not dip in.",
        "sextant_step_touch",
    )
    val touchMoon = SextantStep(
        "touch_moon", "Lit edge on the horizon",
        "Put the bright edge of the Moon on the horizon: the lower limb if the bottom is lit, otherwise the upper " +
            "limb (choose the matching limb in the app). Avoid sights when the Moon's horns point up and down, and " +
            "beware of a false horizon from moonlight on the water at night.",
        "sextant_step_touch_moon",
    )
    val touchStar = SextantStep(
        "touch_star", "Star centred on the horizon",
        "Stars and planets are points: put the dot right on the horizon line at the bottom of the swing.",
        "sextant_step_touch_star",
    )
    val mark = SextantStep(
        "mark", "Mark the time",
        "The instant the body touches the horizon, tap MARK TIME in the workbook (or say \"mark\" to a helper who " +
            "taps it). It writes the time, to the second, into that shot's time box. You can also type a time from " +
            "your watch, or correct the one marked.\n\n" +
            "Every 4 seconds of error can move your position up to 1 nautical mile.",
        "sextant_step_mark_button",
    )
    val read = SextantStep(
        "read", "Read the sextant",
        "1. Degrees: on the arc, the whole degree at or just below the index arm's arrow. Example: 34°.\n\n" +
            "2. Minutes: on the micrometer drum, the minute mark at or just below the index line. Example: 22'.\n\n" +
            "3. Tenths: on the small vernier beside the drum, find the one line that lines up exactly with a drum " +
            "line. Its number is the tenths. Example: 4 = 0.4'.\n\n" +
            "Add them up: 34° + 22' + 0.4' = 34°22.4'. Type it as 34 22.4. Check you haven't misread a degree " +
            "(39°58' not 40°58').",
        "sextant_step_read", "sextant_step_read_drum",
    )
    val repeat = SextantStep(
        "repeat", "Take 3 to 5 sights",
        "Repeat the sight 3 to 5 times a minute or so apart. The app averages them and drops any that don't fit " +
            "the trend, which is far more accurate than one sight.",
        "sextant_step_repeat",
    )

    val rate = SextantStep(
        "rate", "Rate the conditions",
        "In the workbook, pick how hard the sight was. Easy: calm, steady deck, sharp horizon. Moderate: some " +
            "motion, fair horizon. Hard: rolling hard, rain or spray, a hazy or dim horizon, or the body low " +
            "(under 15°).\n\n" +
            "Harder sights count for less in the fix, and make the circle round the fix bigger: that circle shows " +
            "where you most likely are. At Beginner level every sight is treated as Moderate.",
        "sextant_step_rate",
    )

    // ---- plotting: what the workbook's small plot shows, done by hand on a plotting sheet

    val plotWhere = SextantStep(
        "plot_where", "Where you looked",
        "You faced the body to measure its height above the horizon. The direction you faced is its true bearing, " +
            "Zn, worked out in the workbook.\n\n" +
            "Plot that compass bearing, not the angle off the bow: the bearing is the same whichever way the boat " +
            "points. The boat's heading only decides which side of the boat you looked from.",
        "sextant_step_plot_where",
    )
    val plotLine = SextantStep(
        "plot_line", "Plot the line",
        "1. Mark the assumed position (AP) from the workbook.\n\n" +
            "2. Draw a line through it toward the body, at Zn: the direction you looked.\n\n" +
            "3. Measure the intercept along it: toward the body if Ho is bigger than Hc, away if smaller (1′ of " +
            "altitude = 1 nautical mile).\n\n" +
            "4. Draw the line of position at right angles there. Everyone on a huge circle round the body's ground " +
            "point sees it at the same height; near you that circle is this straight line. You are somewhere on it.\n\n" +
            "Each sight in the workbook has this plot at the end; tap it to enlarge.",
        "sextant_step_plot_line",
    )
    val plotFix = SextantStep(
        "plot_fix", "Cross the lines for a fix",
        "Plot a line for each body. Where the lines cross is your fix. Bodies spread round the sky (about 120° " +
            "apart for three) cross at good angles; two bodies close in bearing give a long, uncertain crossing.\n\n" +
            "If the boat moved between sights, slide each earlier line along the course by the distance run (a " +
            "running fix); the workbook does this for you.\n\n" +
            "The circle round the fix is where you most likely are, about 95% of the time. The fix plot at the end " +
            "of the workbook shows your lines, the circle and the GPS position.",
        "sextant_step_plot_fix",
    )

    /** The steps for a body, in order. Index error once per session (first time only). */
    fun steps(kind: BodyKind, includeIndexError: Boolean = true): List<SextantStep> = buildList {
        if (includeIndexError) add(indexError)
        if (kind == BodyKind.SUN) add(shades)
        add(preset); add(bringDown); add(rock)
        add(when (kind) { BodyKind.SUN -> touchSun; BodyKind.MOON -> touchMoon; else -> touchStar })
        add(mark); add(read); add(repeat); add(rate)
        add(plotWhere); add(plotLine); add(plotFix)
    }

    /** All distinct steps (for preloading the pictures). */
    val all: List<SextantStep> = listOf(indexError, shades, preset, bringDown, rock, touchSun, touchMoon, touchStar, mark, read, repeat, rate,
        plotWhere, plotLine, plotFix)
}

/** A plain-language suggestion: shoot this, at about this time, preset to this, face this way. */
data class Suggestion(
    val target: Target,
    val headline: String,           // "Vega: preset 52°, face 078° (ENE)"
    val steps: List<SextantStep>,   // beginner only; empty otherwise
)

/** What the trainer shows for a level. */
data class Guidance(
    val level: SkillLevel,
    val plan: SightPlan?,               // null for ADVANCED
    val suggestions: List<Suggestion>,  // what to shoot today, in time order; empty for ADVANCED
    val tips: List<String>,
)

object Coach {

    fun guidance(level: SkillLevel, pos: LatLon, dayStartMs: Long, heightOfEyeFt: Double = 8.0): Guidance {
        if (level == SkillLevel.ADVANCED) return Guidance(level, null, emptyList(), emptyList())
        val plan = SightPlanner.plan(pos, dayStartMs, heightOfEyeFt)
        val beginner = level == SkillLevel.BEGINNER
        var firstSight = true
        fun s(t: Target): Suggestion {
            val steps = if (beginner) SextantProcedure.steps(t.body.kind, includeIndexError = firstSight) else emptyList()
            firstSight = false
            return Suggestion(t, "${t.body.name}: preset %.0f°, face %03.0f° (%s)".format(t.altitudeDeg, t.azimuthDeg, compassPoint(t.azimuthDeg)), steps)
        }
        val out = mutableListOf<Suggestion>()
        plan.morningStars?.picks?.forEach { out += s(it) }
        plan.sun.morning?.let { out += s(it) }
        plan.moon?.let { out += s(it.moon) }
        plan.sun.noon?.let { out += s(it) }
        plan.sun.afternoon?.let { out += s(it) }
        plan.eveningStars?.picks?.forEach { out += s(it) }
        val tips = buildList {
            addAll(plan.notes)
            if (beginner) {
                add("Start with the Sun: it's easy to find and gives a line of position any clear day.")
                add("The noon sight needs no exact time: follow the Sun up until it stops rising, and take the highest reading. It gives your latitude directly.")
                add("Star sights only work in twilight, when you can see both the stars and a sharp horizon. Be ready with the sextant preset before the window opens.")
                add("Stay between 15° and 75° altitude: lower, and refraction is large and uncertain; higher, and it's hard to swing the arc.")
            }
        }
        return Guidance(level, plan, out.sortedBy { it.target.timeMs }, tips)
    }

    /** 16-point compass name for a bearing. */
    fun compassPoint(deg: Double): String {
        val pts = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return pts[((norm360(deg) + 11.25) / 22.5).toInt() % 16]
    }
}
