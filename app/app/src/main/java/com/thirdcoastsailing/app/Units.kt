package com.thirdcoastsailing.app

/* Unit conversions used across the app (one copy of each). */

/** Feet per nautical mile. */
const val FT_PER_NM = 6076.12
/** Metres per nautical mile. */
const val M_PER_NM = 1852.0
/** Feet per metre. */
const val FT_PER_M = 3.28084
/** Knots per metre per second. */
const val MS_TO_KN = 1.943844
/** One hour in milliseconds. */
const val HOUR_MS = 3_600_000L

/**
 * A number typed by the user, accepting a decimal comma ("4,9") as phones in many languages type it; null when it
 * isn't a finite number.
 */
fun String.toDecimalOrNull(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
