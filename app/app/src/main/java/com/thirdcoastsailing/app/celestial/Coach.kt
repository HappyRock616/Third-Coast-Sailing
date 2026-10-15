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

/** One step of using the sextant. `graphic` is the drawable resource name of its picture. */
data class SextantStep(val id: String, val title: String, val text: String, val graphic: String)

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
        "sextant_step_touch",
    )
    val touchStar = SextantStep(
        "touch_star", "Star centred on the horizon",
        "Stars and planets are points: put the dot right on the horizon line at the bottom of the swing.",
        "sextant_step_touch",
    )
    val mark = SextantStep(
        "mark", "Mark the time",
        "The instant it touches, tap MARK (or say \"mark\" to a helper). The app records the time to the second; " +
            "every 4 seconds of error moves your position up to 1 nautical mile.",
        "sextant_step_mark",
    )
    val read = SextantStep(
        "read", "Read the sextant",
        "Degrees from the index arm against the arc, minutes from the micrometer drum, tenths from the small " +
            "vernier next to the drum. Example: arc 34°, drum 22', vernier 0.4' = 34°22.4'. Check you haven't " +
            "misread a degree (39°58' not 40°58').",
        "sextant_step_read",
    )
    val repeat = SextantStep(
        "repeat", "Take 3 to 5 sights",
        "Repeat the sight 3 to 5 times a minute or so apart. The app averages them and drops any that don't fit " +
            "the trend, which is far more accurate than one sight.",
        "sextant_step_mark",
    )

    /** The steps for a body, in order. Index error once per session (first time only). */
    fun steps(kind: BodyKind, includeIndexError: Boolean = true): List<SextantStep> = buildList {
        if (includeIndexError) add(indexError)
        if (kind == BodyKind.SUN) add(shades)
        add(preset); add(bringDown); add(rock)
        add(when (kind) { BodyKind.SUN -> touchSun; BodyKind.MOON -> touchMoon; else -> touchStar })
        add(mark); add(read); add(repeat)
    }

    /** All distinct steps (for preloading the pictures). */
    val all: List<SextantStep> = listOf(indexError, shades, preset, bringDown, rock, touchSun, touchMoon, touchStar, mark, read, repeat)
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
