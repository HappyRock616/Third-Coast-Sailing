package com.thirdcoastsailing.app

import kotlin.math.abs

/*
 * Light flash patterns (no Android parts, so it can be tested on its own).
 * The chart files carry NOAA's light data: the pattern ("Fl", "Fl(2)", "Al Fl", "Mo(A)" ...), its colours as S-57
 * codes ("1,3" = white, red), the period in seconds and, for about two-thirds of the lights, NOAA's exact on/off
 * sequence ("00.1+(04.9)+00.1+(04.9)": on 0.1 s, off 4.9 s, ...). The sequence is used when present; otherwise the
 * timing comes from the usual rules for the pattern. Flashes shorter than MIN_ON_S are stretched so the eye catches
 * them on a phone; the period stays exact.
 */

/** One "on" part of a period: from..to seconds into the period, in colour code `colour` ("1" white, "3" red ...). */
data class LightOn(val from: Double, val to: Double, val colour: String)

/** A light's timing over one period. */
data class LightPattern(val periodS: Double, val on: List<LightOn>) {
    /** Colour code lit at `t` seconds (any time; the pattern repeats), or null while dark. */
    fun colourAt(t: Double): String? {
        if (on.isEmpty()) return null
        val p = ((t % periodS) + periodS) % periodS
        return on.firstOrNull { p >= it.from && p < it.to }?.colour
    }

    /** How lit at `t`: 1 while on, fading to 0 over the last FADE_S of each flash, 0 while dark. */
    fun levelAt(t: Double): Float {
        val p = ((t % periodS) + periodS) % periodS
        val o = on.firstOrNull { p >= it.from && p < it.to } ?: return 0f
        val left = o.to - p
        return if (left >= FADE_S || o.to - o.from >= periodS) 1f else (left / FADE_S).toFloat()
    }

    companion object {
        const val MIN_ON_S = 0.3
        const val FADE_S = 0.15

        /**
         * @param chr     pattern abbreviation from the chart file ("Fl", "Fl(2)", "Al Fl", "Q", "Iso", "Oc", "Mo(A)", "F")
         * @param colours S-57 colour codes, "1,3"
         * @param periodS signal period (null for fixed lights)
         * @param seq     NOAA SIGSEQ, e.g. "00.4+(03.6)" (null when not charted)
         */
        fun of(chr: String, colours: String, periodS: Double?, seq: String?): LightPattern {
            val cols = colours.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1") }
            val base = chr.removePrefix("AERO ").trim()
            if (base == "F" || periodS == null || periodS <= 0.0) return LightPattern(1.0, listOf(LightOn(0.0, 1.0, cols[0])))
            val parts = seq?.let { parseSeq(it) }
            val alternating = base.startsWith("Al")
            val raw = when {
                parts != null && abs(parts.sumOf { it.second } - periodS) < 0.6 -> parts
                // alternating, no sequence: one flash per colour, evenly spaced over the period
                alternating && cols.size > 1 -> cols.flatMap { listOf(Pair(true, 0.5), Pair(false, periodS / cols.size - 0.5)) }
                else -> rule(base, periodS)
            }
            // turn on/off parts into lit spans; alternating lights step through their colours flash by flash
            val on = ArrayList<LightOn>()
            var t = 0.0; var k = 0
            for ((isOn, d) in raw) {
                if (isOn) {
                    val c = if (alternating) cols[k % cols.size] else cols[0]
                    on += LightOn(t, minOf(t + maxOf(d, MIN_ON_S), periodS), c); k++
                }
                t += d
            }
            return LightPattern(periodS, on)
        }

        /** "00.1+(04.9)+00.1+(04.9)" -> [(on, 0.1), (off, 4.9), ...]; null if it can't be read. */
        fun parseSeq(seq: String): List<Pair<Boolean, Double>>? = runCatching {
            seq.split('+').map { s ->
                val off = s.trim().startsWith("(")
                Pair(!off, s.trim().trim('(', ')').toDouble())
            }.takeIf { it.isNotEmpty() && it.any { p -> p.first } }
        }.getOrNull()

        /** Timing by the usual rules when NOAA gives no sequence. */
        fun rule(chr: String, per: Double): List<Pair<Boolean, Double>> {
            val group = Regex("\\(([^)]*)\\)").find(chr)?.groupValues?.get(1) ?: ""
            val kind = chr.substringBefore('(').removePrefix("Al").trim()
            fun flashes(n: Int, on: Double, gap: Double): List<Pair<Boolean, Double>> {
                val out = ArrayList<Pair<Boolean, Double>>()
                repeat(n) { i -> out += Pair(true, on); if (i < n - 1) out += Pair(false, gap) }
                val used = n * on + (n - 1) * gap
                out += Pair(false, maxOf(per - used, 0.1))
                return out
            }
            val n = group.split('+').mapNotNull { it.trim().toIntOrNull() }.sum().takeIf { it > 0 } ?: 1
            return when (kind) {
                "Iso" -> listOf(Pair(true, per / 2), Pair(false, per / 2))
                "Oc" -> { val off = minOf(maxOf(1.0, per / 4), per / 2); listOf(Pair(true, per - off), Pair(false, off)) }
                "LFl" -> flashes(n, 2.0, 2.0)
                "Q", "IQ" -> flashes(n, 0.3, 0.7)
                "VQ", "IVQ" -> flashes(n, 0.3, 0.2)
                "Mo" -> morse(group, per)
                else -> flashes(n, 0.5, 1.0)                    // Fl, Fl(2), and anything unusual
            }
        }

        private val MORSE = mapOf('A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
            'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--",
            'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
            'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..")

        /** Morse letter: dot 0.4 s, dash 1.5 s, 0.6 s between, the rest of the period dark. */
        private fun morse(letters: String, per: Double): List<Pair<Boolean, Double>> {
            val code = letters.uppercase().mapNotNull { MORSE[it] }.joinToString("")
            if (code.isEmpty()) return listOf(Pair(true, 0.5), Pair(false, per - 0.5))
            val out = ArrayList<Pair<Boolean, Double>>()
            code.forEachIndexed { i, c -> out += Pair(true, if (c == '.') 0.4 else 1.5); if (i < code.length - 1) out += Pair(false, 0.6) }
            out += Pair(false, maxOf(per - out.sumOf { it.second }, 0.5))
            return out
        }

        /** "white", "red" ... */
        fun colourWord(code: String): String = when (code) {
            "1" -> "white"; "3" -> "red"; "4" -> "green"; "6" -> "yellow"; "5" -> "blue"; "11" -> "orange"
            else -> "colored"
        }

        /** One line in plain words: "White and red, alternating, every 5 s", "Green flash every 4 s". */
        fun plainWords(chr: String, colours: String, periodS: Double?, seq: String?): String {
            val cols = colours.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1") }
            val words = cols.map { colourWord(it) }
            val c = words.first().replaceFirstChar { it.uppercase() }
            val per = periodS?.let { fmt(it) }
            val base = chr.removePrefix("AERO ").trim()
            val group = Regex("\\(([^)]*)\\)").find(base)?.groupValues?.get(1) ?: ""
            val kind = base.substringBefore('(').trim()
            val aero = if (chr.startsWith("AERO")) " (aviation light)" else ""
            return when {
                kind == "F" || per == null -> "Steady ${words.joinToString(" and ")} light$aero"
                kind.startsWith("Al") -> {
                    val flashesPerPeriod = seq?.let { parseSeq(it) }?.count { it.first } ?: words.size
                    val every = fmt((periodS ?: 0.0) / flashesPerPeriod.coerceAtLeast(1))
                    "${words.joinToString(" and ").replaceFirstChar { it.uppercase() }}, alternating, every $every s$aero"
                }
                kind == "Fl" && group.isNotEmpty() && group != "1" -> "$c flashes in groups of $group, every $per s$aero"
                kind == "Fl" -> "$c flash every $per s$aero"
                kind == "LFl" -> "Long ${words.first()} flash every $per s$aero"
                kind == "Q" -> "Quick ${words.first()} flashes, 60 a minute$aero"
                kind == "VQ" -> "Very quick ${words.first()} flashes, 120 a minute"
                kind == "IQ" -> "Quick ${words.first()} flashes, with breaks, every $per s"
                kind == "Iso" -> "$c, equal on and off, every $per s"
                kind == "Oc" -> "$c, on longer than off, every $per s"
                kind == "Mo" -> "$c Morse \"$group\" every $per s"
                else -> "$c light, $chr every $per s"
            }
        }

        private fun fmt(s: Double): String = if (abs(s - Math.round(s)) < 0.05) Math.round(s).toString() else "%.1f".format(s)
    }
}
