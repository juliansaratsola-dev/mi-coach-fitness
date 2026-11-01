package dev.juliansaratsola.micoachfitness

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text block injected, hidden from the user, into the Gemini prompts: the current Form Score plus the latest
 * workouts, so the coach judges today's session against the user's own consistency instead of in a vacuum.
 */
object HistoricalContext {
    const val SESSION_LIMIT = 5
    private const val TREND_WINDOW = 5

    fun build(history: List<WorkoutRecord>, today: LocalDate = LocalDate.now(), limit: Int = SESSION_LIMIT): String {
        val recent = latest(history, limit)
        val sb = StringBuilder()
        sb.append(formScoreLine(FormScore.snapshot(history, today))).append('\n')
        if (recent.isEmpty()) {
            sb.append("HISTORIAL: sin entrenamientos previos registrados.\n")
        } else {
            sb.append("ÚLTIMOS ${recent.size} ENTRENOS (del más antiguo al más reciente):\n")
            recent.forEach { sb.append("- ").append(it.toHistoryLine()).append('\n') }
            trendSummary(recent)?.let { sb.append('\n').append(it).append('\n') }
        }
        return sb.toString()
    }

    /** The last [limit] workouts by date, oldest first. A workout with an invalid date counts as the oldest. */
    fun latest(history: List<WorkoutRecord>, limit: Int = SESSION_LIMIT): List<WorkoutRecord> =
        history.sortedBy { it.localDate() ?: LocalDate.MIN }.takeLast(limit.coerceAtLeast(0))

    /** Averages of the latest workouts, so the model compares against numbers instead of guessing. Effort first, pace last. */
    fun trendSummary(history: List<WorkoutRecord>): String? {
        val window = history.takeLast(TREND_WINDOW)
        if (window.isEmpty()) return null
        val items = mutableListOf<String>()
        items += "esfuerzo medio ${window.map { it.puntosEsfuerzo }.average().roundToInt()} pts"
        window.mapNotNull { it.durationMin }.averageOrNull()?.let { items += "duración ${it.roundToInt()} min" }
        window.mapNotNull { it.avgHr }.averageOrNull()?.let { items += "FC media ${it.toInt()} ppm" }
        window.mapNotNull { it.cadence }.averageOrNull()?.let { items += "cadencia ${it.toInt()} ppm" }
        window.mapNotNull { it.contactMs }.averageOrNull()?.let { items += "contacto suelo ${it.toInt()} ms" }
        window.mapNotNull { it.distanceKm }.averageOrNull()?.let {
            items += String.format(Locale.US, "distancia %.1f km", it)
        }
        val trench = window.count { it.esDiaTrinchera }
        if (trench > 0) items += "días de trinchera: $trench de ${window.size}"
        window.mapNotNull { it.paceSecPerKm }.averageOrNull()?.let {
            items += "ritmo medio ${WorkoutRecord.formatPace(it.toInt())}/km (dato secundario)"
        }
        return "PROMEDIOS de las últimas ${window.size} sesiones: ${items.joinToString(", ")}."
    }

    private fun formScoreLine(snapshot: FormSnapshot): String {
        val level = "nivel ${snapshot.level.number}, ${snapshot.level.title}"
        val daysSinceLast = snapshot.daysSinceLast ?: return "FORM SCORE ACTUAL: 0 ($level)."
        val trained = if (snapshot.trainedDays == 1) "día entrenado" else "días entrenados"
        return "FORM SCORE ACTUAL: ${snapshot.scoreWhole} ($level). " +
            "Esfuerzo de los últimos ${FormScore.WINDOW_DAYS} días: ${snapshot.windowPoints} pts en " +
            "${snapshot.trainedDays} $trained; último entreno ${daysAgo(daysSinceLast)}. " +
            "La gravedad divide esa suma entre ${daysSinceLast + 1}."
    }

    private fun daysAgo(days: Int): String = when (days) {
        0 -> "hoy"
        1 -> "hace 1 día"
        else -> "hace $days días"
    }

    private fun List<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else map { it.toDouble() }.average()
}
