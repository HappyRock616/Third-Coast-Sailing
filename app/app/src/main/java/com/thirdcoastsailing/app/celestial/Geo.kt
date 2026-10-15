package com.thirdcoastsailing.app.celestial

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Small spherical-earth helpers for celestial navigation (pure Kotlin, no Android, so it can be unit-tested
 * on a PC). Distances are nautical miles: 1 nm = 1 arc-minute of latitude, which is the unit celestial
 * navigation works in.
 */

/** A position. lat north +, lon east + (degrees). */
data class LatLon(val lat: Double, val lon: Double)

internal const val DEG = PI / 180.0
internal const val RAD = 180.0 / PI

/** Normalise an angle to [0, 360). */
fun norm360(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

/** Normalise an angle to (-180, 180]. */
fun norm180(a: Double): Double { val x = norm360(a); return if (x > 180.0) x - 360.0 else x }

/** Point reached from `from` after `nm` nautical miles on a true bearing (great circle). */
fun travel(from: LatLon, bearingDeg: Double, nm: Double): LatLon {
    val d = nm / 60.0 * DEG
    val th = bearingDeg * DEG
    val p1 = from.lat * DEG; val l1 = from.lon * DEG
    val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(th))
    val l2 = l1 + atan2(sin(th) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    return LatLon(p2 * RAD, norm180(l2 * RAD))
}

/** Great-circle distance in nautical miles. */
fun distanceNm(a: LatLon, b: LatLon): Double {
    val p1 = a.lat * DEG; val p2 = b.lat * DEG
    val dp = p2 - p1; val dl = (b.lon - a.lon) * DEG
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * asin(sqrt(h.coerceIn(0.0, 1.0))) * RAD * 60.0
}

/** Initial true bearing from a to b (degrees). */
fun bearing(a: LatLon, b: LatLon): Double {
    val p1 = a.lat * DEG; val p2 = b.lat * DEG; val dl = (b.lon - a.lon) * DEG
    return norm360(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)) * RAD)
}

/** Degrees as navigators write them: 42°31.4'N / 086°12.0'W. */
fun formatLat(lat: Double): String = dm(kotlin.math.abs(lat), 2) + if (lat >= 0) "N" else "S"
fun formatLon(lon: Double): String = dm(kotlin.math.abs(lon), 3) + if (lon >= 0) "E" else "W"

/** An angle as degrees and decimal minutes, e.g. 42°31.4'. Negative values get a leading minus. */
fun formatDm(deg: Double, width: Int = 1): String = (if (deg < 0) "-" else "") + dm(kotlin.math.abs(deg), width)

private fun dm(a: Double, width: Int): String {
    var d = a.toInt(); var m = (a - d) * 60.0
    if (m >= 59.95) { d += 1; m = 0.0 }
    return "%0${width}d°%04.1f'".format(d, m)
}
