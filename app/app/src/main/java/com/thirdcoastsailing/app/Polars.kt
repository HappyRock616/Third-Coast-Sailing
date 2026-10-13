package com.thirdcoastsailing.app

import android.content.Context
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Speed polar: boat speed (kn) on a grid of true wind angle (rows, deg) x true wind speed (columns, kn).
 * Stored and exchanged in the common .pol text format (tab separated, first row "TWA\TWS" + wind speeds).
 */
data class Polar(val tws: List<Double>, val twa: List<Double>, val speed: List<List<Double>>) {

    /** Bilinear interpolation of boat speed at any TWS/TWA (TWA folded to 0..180). */
    fun boatSpeed(twsKn: Double, twaDeg: Double): Double {
        val a = foldAngle(twaDeg)
        val (i0, i1, fa) = bracket(twa, a)
        val (j0, j1, fs) = bracket(tws, twsKn)
        val top = speed[i0][j0] * (1 - fs) + speed[i0][j1] * fs
        val bot = speed[i1][j0] * (1 - fs) + speed[i1][j1] * fs
        return max(0.0, top * (1 - fa) + bot * fa)
    }

    fun toPol(): String = buildString {
        append("TWA\\TWS\t").append(tws.joinToString("\t") { fmt(it) }).append('\n')
        twa.forEachIndexed { i, a ->
            append(fmt(a)).append('\t').append(speed[i].joinToString("\t") { "%.2f".format(it) }).append('\n')
        }
    }

    companion object {
        fun parse(text: String): Polar {
            val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split('\t', ';', ',', ' ').filter { s -> s.isNotBlank() } }
            val tws = rows.first().drop(1).map { it.toDouble() }
            val body = rows.drop(1)
            return Polar(tws, body.map { it[0].toDouble() }, body.map { r -> r.drop(1).map { it.toDouble() } })
        }

        private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

        /** Cell-by-cell maximum of several polars on the same grid (base sails + enabled spinnakers). */
        fun maxOf(polars: List<Polar>): Polar {
            val p0 = polars.first()
            return p0.copy(speed = p0.speed.indices.map { i ->
                p0.speed[i].indices.map { j -> polars.maxOf { it.speed[i][j] } }
            })
        }
    }
}

/** Index pair around v in a sorted axis, plus the fraction between them (clamped at the ends). */
private fun bracket(axis: List<Double>, v: Double): Triple<Int, Int, Double> {
    if (v <= axis.first()) return Triple(0, 0, 0.0)
    if (v >= axis.last()) return Triple(axis.lastIndex, axis.lastIndex, 0.0)
    val i = axis.indexOfLast { it <= v }
    return Triple(i, i + 1, (v - axis[i]) / (axis[i + 1] - axis[i]))
}

private fun foldAngle(a: Double): Double {
    var x = ((a % 360) + 360) % 360
    if (x > 180) x = 360 - x
    return x
}

/** A manual observation as read off the instruments: apparent wind speed/angle and boat speed. */
data class ManualPoint(val awsKn: Double, val awaDeg: Double, val bspKn: Double) {
    /** Convert apparent wind to true wind (boat speed through water, no current). Returns (TWS kn, TWA deg). */
    fun toTrue(): Pair<Double, Double> {
        val awa = foldAngle(awaDeg) * PI / 180
        val x = awsKn * cos(awa) - bspKn      // along the boat's heading
        val y = awsKn * sin(awa)
        return Pair(hypot(x, y), atan2(y, x) * 180 / PI)
    }
}

object PolarMath {
    /**
     * Refit a polar to manual observations. Each observation sets the ratio (observed / predicted)
     * at its own true wind point; nearby grid cells move toward that ratio with a smooth falloff
     * (about 4 kn of wind speed and 15 degrees of wind angle). Cells far from any observation are unchanged.
     */
    fun refit(base: Polar, points: List<ManualPoint>): Polar {
        if (points.isEmpty()) return base
        val obs = points.mapNotNull { p ->
            val (tws, twa) = p.toTrue()
            val pred = base.boatSpeed(tws, twa)
            if (pred < 0.3) null else Triple(tws, twa, p.bspKn / pred)
        }
        if (obs.isEmpty()) return base
        val prior = 0.15                              // pull toward the template where data is thin
        val newSpeed = base.twa.indices.map { i ->
            base.tws.indices.map { j ->
                var wSum = prior
                var rSum = prior * 1.0
                for ((tws, twa, r) in obs) {
                    val ds = (base.tws[j] - tws) / 4.0
                    val da = (base.twa[i] - twa) / 15.0
                    val w = exp(-(ds * ds + da * da))
                    wSum += w; rSum += w * r
                }
                base.speed[i][j] * (rSum / wSum)
            }
        }
        return base.copy(speed = newSpeed)
    }
}

/** Template polars bundled with the app (assets/polars), listed by length only. */
object PolarTemplates {
    val names = listOf("27ft", "30ft", "36ft", "37.5ft")
    const val DEFAULT = "37.5ft"

    fun load(context: Context, name: String, sym: Boolean, asym: Boolean): Polar {
        fun read(file: String) = Polar.parse(context.assets.open("polars/$file").bufferedReader().readText())
        val parts = mutableListOf(read("$name.pol"))
        if (sym) parts += read("${name}_sym.pol")
        if (asym) parts += read("${name}_asym.pol")
        return Polar.maxOf(parts)
    }
}
