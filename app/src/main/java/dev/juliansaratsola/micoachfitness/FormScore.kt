package dev.juliansaratsola.micoachfitness

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Effort of one calendar day, one bar of the consistency chart. */
data class DayEffort(val date: LocalDate, val points: Int, val isTrenchDay: Boolean, val sessions: Int) {
    val trained: Boolean get() = sessions > 0
}

/** RPG-style level. [progress] is the fraction (0..1) walked from [floorScore] towards [nextScore]. */
data class FormLevel(
    val number: Int,
    val title: String,
    val progress: Float,
    val floorScore: Double,
    val nextScore: Double
)

data class FormSnapshot(
    val score: Double,
    /** Effort points accumulated in the last [FormScore.WINDOW_DAYS] days. */
    val windowPoints: Int,
    val sessionsInWindow: Int,
    /** Distinct days with a workout inside the window. */
    val trainedDays: Int,
    /** Days between the last workout and today; null when nothing was ever recorded. */
    val daysSinceLast: Int?,
    val level: FormLevel
) {
    /** The score as shown to the user. Rounded down, so a displayed value never claims a level that is not reached. */
    val scoreWhole: Int get() = floor(score).toInt()

    /** Whole points still missing to reach the next level (at least 1). */
    val pointsToNextLevel: Int get() = ceil(level.nextScore - score).toInt().coerceAtLeast(1)

    /** What the 30-day effort is divided by: days since the last workout + 1. Null when nothing was recorded. */
    val gravityDivisor: Int? get() = daysSinceLast?.plus(1)
}

/**
 * "Gravity": the Form Score is the effort of the last 30 days divided by (days since the last workout + 1), so it
 * decays while the user does not train. Nobody lives off past success.
 */
object FormScore {
    const val WINDOW_DAYS = 30

    /** Points the chart scale never drops below, so one small workout does not look like a huge bar. */
    const val MIN_CHART_SCALE = 50

    /** Score needed for level n is LEVEL_UNIT * (n - 1)^2: each new level costs more than the previous one. */
    private const val LEVEL_UNIT = 25.0

    fun calculate(sessions: List<WorkoutRecord>, today: LocalDate = LocalDate.now()): Double =
        snapshot(sessions, today).score

    /** Workouts dated in the future or with an invalid date are ignored. */
    fun snapshot(sessions: List<WorkoutRecord>, today: LocalDate = LocalDate.now()): FormSnapshot {
        val dated = sessions.mapNotNull { record ->
            record.localDate()?.takeIf { !it.isAfter(today) }?.let { it to record }
        }
        val windowStart = today.minusDays((WINDOW_DAYS - 1).toLong())
        val inWindow = dated.filter { !it.first.isBefore(windowStart) }
        val windowPoints = inWindow.sumOf { it.second.puntosEsfuerzo }
        val daysSinceLast = dated.maxOfOrNull { it.first }?.let { ChronoUnit.DAYS.between(it, today).toInt() }
        val score = if (daysSinceLast == null) 0.0 else windowPoints.toDouble() / (daysSinceLast + 1)
        return FormSnapshot(
            score = score,
            windowPoints = windowPoints,
            sessionsInWindow = inWindow.size,
            trainedDays = inWindow.map { it.first }.distinct().size,
            daysSinceLast = daysSinceLast,
            level = levelFor(score)
        )
    }

    fun levelFor(score: Double): FormLevel {
        val safe = if (score.isFinite()) score.coerceAtLeast(0.0) else 0.0
        val number = 1 + floor(sqrt(safe / LEVEL_UNIT)).toInt()
        val floorScore = LEVEL_UNIT * (number - 1) * (number - 1)
        val nextScore = LEVEL_UNIT * number * number
        val progress = ((safe - floorScore) / (nextScore - floorScore)).toFloat().coerceIn(0f, 1f)
        return FormLevel(number, titleFor(number), progress, floorScore, nextScore)
    }

    fun titleFor(level: Int): String = when {
        level <= 1 -> "Oxidado"
        level <= 3 -> "Recluta"
        level <= 5 -> "Soldado"
        level <= 8 -> "Veterano"
        level <= 11 -> "Curtido"
        level <= 15 -> "Forjado"
        else -> "Inquebrantable"
    }

    /** The last [WINDOW_DAYS] days, oldest first and ending today. Days without workouts have no sessions. */
    fun dailyEffort(sessions: List<WorkoutRecord>, today: LocalDate = LocalDate.now()): List<DayEffort> {
        val byDate = sessions
            .mapNotNull { record -> record.localDate()?.let { it to record } }
            .groupBy({ it.first }, { it.second })
        return (WINDOW_DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val day = byDate[date].orEmpty()
            DayEffort(date, day.sumOf { it.puntosEsfuerzo }, day.any { it.esDiaTrinchera }, day.size)
        }
    }

    /** Points that map to a full-height bar. */
    fun chartScale(days: List<DayEffort>): Int = max(days.maxOfOrNull { it.points } ?: 0, MIN_CHART_SCALE)
}
