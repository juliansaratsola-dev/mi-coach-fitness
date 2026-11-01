package dev.juliansaratsola.micoachfitness

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Result of [EffortCalculator.evaluateTrenchDay]. */
data class TrenchEvaluation(val isTrenchDay: Boolean, val multiplier: Double)

/** What the effort of a session was computed from. Anything but [MEASURED] means a standard value filled a gap. */
enum class EffortBasis { MEASURED, NO_HEART_RATE, NO_DURATION, NO_DATA }

/** Final effort of one session: the points to persist and how they were obtained. */
data class SessionEffort(
    val points: Int,
    val isTrenchDay: Boolean,
    val multiplier: Double,
    val basis: EffortBasis
) {
    /** True when heart rate or duration were missing and a standard value was used instead. */
    val estimated: Boolean get() = basis != EffortBasis.MEASURED
}

/** Estimated minutes per heart-rate zone. Minutes below zone 1 carry no weight. */
data class ZoneMinutes(val belowZone1: Double, val zones: List<Double>) {
    val total: Double get() = belowZone1 + zones.sum()
}

/**
 * Internal-load ("TRIMP", Edwards style) effort model. It is independent of the AI and ignores pace:
 * effort = time under tension x cardiac demand.
 */
object EffortCalculator {
    /** Weight per minute of zones 1..5. Time below 50 % of max HR does not count. */
    val ZONE_WEIGHTS: List<Double> = listOf(1.0, 2.0, 3.0, 4.0, 5.0)

    const val TRENCH_MULTIPLIER = 1.2

    /** Base points from which an effort counts as "high" for a trench day (≈ 50 min easy or 25 min hard). */
    const val HIGH_EFFORT_THRESHOLD = 100.0

    const val MIN_AGE = 10
    const val MAX_AGE = 100

    /** Max HR assumed when the age is unknown. */
    const val DEFAULT_MAX_HR = 190.0

    /** Standard effort when there is no heart rate: all the time in zone 2. */
    const val STANDARD_WEIGHT_PER_MINUTE = 2.0
    const val STANDARD_DURATION_MIN = 30.0
    const val MAX_DURATION_MIN = 1440.0

    private const val MIN_VALID_HR = 30
    private const val MAX_VALID_HR = 250

    /** Fraction of max HR where zones 1..5 start. */
    private val ZONE_FLOORS = doubleArrayOf(0.50, 0.60, 0.70, 0.80, 0.90)

    /** Session max HR is taken as roughly 2.5 standard deviations above the average. */
    private const val PEAK_SIGMAS = 2.5
    private const val MIN_SPREAD = 0.03
    private const val MAX_SPREAD = 0.10
    private const val DEFAULT_SPREAD = 0.05

    /**
     * Base effort of a session. Only summary data exists (average and peak HR), so the minutes per zone
     * are estimated assuming the HR followed a normal curve centred on [avgHR]; [maxHR] sets its spread
     * (intervals spread wider than a steady run). Zones are fractions of the max HR estimated as 220 - [userAge]
     * (190 when the age is unknown).
     *
     * Missing data never throws: with no valid HR the standard effort ([STANDARD_WEIGHT_PER_MINUTE] per
     * minute) is used, and a missing duration counts as [STANDARD_DURATION_MIN].
     */
    fun calculateEffortScore(durationMinutes: Double?, avgHR: Int?, maxHR: Int?, userAge: Int?): Double {
        val minutes = estimateZoneMinutes(durationMinutes, avgHR, maxHR, userAge)
        return minutes.zones.indices.sumOf { minutes.zones[it] * ZONE_WEIGHTS[it] }
    }

    fun estimateZoneMinutes(durationMinutes: Double?, avgHR: Int?, maxHR: Int?, userAge: Int?): ZoneMinutes {
        val duration = validDuration(durationMinutes) ?: STANDARD_DURATION_MIN
        val avg = validHeartRate(avgHR) ?: return ZoneMinutes(0.0, listOf(0.0, duration, 0.0, 0.0, 0.0))
        val reference = maxOf(referenceMaxHr(userAge), avg.toDouble())
        val peak = maxHR?.takeIf { it in avg..MAX_VALID_HR }
        val spread = if (peak != null) (peak - avg) / PEAK_SIGMAS else reference * DEFAULT_SPREAD
        val sigma = spread.coerceIn(reference * MIN_SPREAD, reference * MAX_SPREAD)
        // Cumulative share of the session below each zone floor, closed with 1.0 for the open top zone.
        val cumulative = ZONE_FLOORS.map { normalCdf((it * reference - avg) / sigma) } + 1.0
        return ZoneMinutes(
            belowZone1 = duration * cumulative[0],
            zones = ZONE_WEIGHTS.indices.map { duration * (cumulative[it + 1] - cumulative[it]) }
        )
    }

    /**
     * Reads the optional "sensaciones" text. Suffering keywords (pesado, cansancio, dolor, calor, lluvia...)
     * together with a high [effortScore] mark a trench day: effort under adverse conditions, worth x1.2.
     *
     * [avgPace] is accepted but never used: a slow or fast pace does not decide anything, effort does.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluateTrenchDay(
        rpeText: String?,
        effortScore: Double,
        avgPace: Int? = null,
        highEffortThreshold: Double = HIGH_EFFORT_THRESHOLD
    ): TrenchEvaluation {
        val isTrench = effortScore >= highEffortThreshold && findSufferingKeywords(rpeText).isNotEmpty()
        return TrenchEvaluation(isTrench, if (isTrench) TRENCH_MULTIPLIER else 1.0)
    }

    /** Full evaluation of a session: base effort, trench multiplier and the rounded points to persist. */
    fun evaluateSession(
        durationMinutes: Double?,
        avgHR: Int?,
        maxHR: Int?,
        userAge: Int?,
        rpeText: String?,
        avgPace: Int? = null
    ): SessionEffort {
        val base = calculateEffortScore(durationMinutes, avgHR, maxHR, userAge)
        val basis = basisOf(durationMinutes, avgHR)
        // Without measured effort there is nothing to reward as resilience.
        val trench = if (basis == EffortBasis.MEASURED) {
            evaluateTrenchDay(rpeText, base, avgPace)
        } else {
            TrenchEvaluation(false, 1.0)
        }
        return SessionEffort(
            points = (base * trench.multiplier).roundToInt(),
            isTrenchDay = trench.isTrenchDay,
            multiplier = trench.multiplier,
            basis = basis
        )
    }

    /** Which of the two inputs of the effort, duration and average heart rate, were really measured. */
    fun basisOf(durationMinutes: Double?, avgHR: Int?): EffortBasis {
        val hasDuration = validDuration(durationMinutes) != null
        val hasHeartRate = validHeartRate(avgHR) != null
        return when {
            hasDuration && hasHeartRate -> EffortBasis.MEASURED
            hasDuration -> EffortBasis.NO_HEART_RATE
            hasHeartRate -> EffortBasis.NO_DURATION
            else -> EffortBasis.NO_DATA
        }
    }

    /** Suffering words found in [text] that are not negated ("sin dolor" does not count). */
    fun findSufferingKeywords(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = linkedSetOf<String>()
        for (clause in CLAUSE_SPLIT.split(normalize(text))) {
            val words = WORD.findAll(clause).map { it.value }.toList()
            words.forEachIndexed { index, word ->
                if (isSufferingWord(word) && !isNegated(words, index)) found += word
            }
        }
        return found.toList()
    }

    private fun isSufferingWord(word: String): Boolean {
        if (word in SUFFERING_WORDS) return true
        if (EXCLUDED_PREFIXES.any { word.startsWith(it) }) return false
        return SUFFERING_STEMS.any { word.startsWith(it) }
    }

    private fun isNegated(words: List<String>, index: Int): Boolean =
        (maxOf(0, index - NEGATION_WINDOW) until index).any { words[it] in NEGATORS }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")

    private fun validHeartRate(hr: Int?): Int? = hr?.takeIf { it in MIN_VALID_HR..MAX_VALID_HR }

    private fun validDuration(minutes: Double?): Double? =
        minutes?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_DURATION_MIN)

    private fun referenceMaxHr(userAge: Int?): Double =
        if (userAge != null && userAge in MIN_AGE..MAX_AGE) 220.0 - userAge else DEFAULT_MAX_HR

    private fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    /** Abramowitz-Stegun 7.1.26, absolute error below 1.5e-7. */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val poly = (((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592
        val magnitude = 1.0 - poly * t * exp(-x * x)
        return if (x < 0) -magnitude else magnitude
    }

    private const val NEGATION_WINDOW = 3
    private val CLAUSE_SPLIT = Regex("[.,;:!?¡¿()\\n\\r]+|\\b(?:pero|aunque|sino)\\b")
    private val WORD = Regex("[a-z0-9]+")
    private val MARKS = Regex("\\p{M}+")
    private val NEGATORS = setOf("sin", "no", "nada", "ni", "nunca", "jamas", "ningun", "ninguna", "ninguno", "cero")
    private val EXCLUDED_PREFIXES = listOf("calori")

    // Matched against the start of accent-less words, so "pesad" covers pesado, pesadas, pesadez.
    private val SUFFERING_STEMS = listOf(
        "pesad", "cansad", "cansanc", "fatig", "agot", "arrastr", "plomo",
        "dolor", "doli", "duele", "molest", "calambre", "malest", "nausea", "mareo", "mareada", "mareado", "sufr",
        "calor", "calur", "bochorn", "sofoc", "humedad", "lluvi", "llov", "chubasc", "tormenta", "granizo",
        "nieve", "viento", "ventos", "frio"
    )

    // Short or ambiguous words that must match exactly.
    private val SUFFERING_WORDS = setOf("mal", "mala", "malo", "malas", "malos", "helada", "heladas")
}
