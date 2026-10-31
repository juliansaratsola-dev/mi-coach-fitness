package dev.juliansaratsola.micoachfitness

import java.util.Locale

/** Workout read from Health Connect (written there by Garmin Connect). Pure Kotlin so it is unit-testable. */
data class HealthSession(
    val startMillis: Long,
    val endMillis: Long,
    val exerciseType: Int,
    val title: String?,
    val distanceM: Double?,
    val avgHr: Long?,
    val maxHr: Long?,
    val avgPowerW: Double?,
    val elevationGainM: Double?,
    val cadenceSpm: Double?,
    val origin: String? = null,
    val minHr: Long? = null,
    val maxPowerW: Double? = null,
    val maxCadenceSpm: Double? = null,
    val maxSpeedKmh: Double? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null
) {
    val durationMin: Double get() = (endMillis - startMillis) / 60_000.0

    fun typeName(): String = when (exerciseType) {
        RUNNING -> "Carrera"
        RUNNING_TREADMILL -> "Carrera en cinta"
        WALKING -> "Caminata"
        BIKING, BIKING_STATIONARY -> "Ciclismo"
        else -> "Entrenamiento (tipo $exerciseType)"
    }

    fun paceSecPerKm(): Int? {
        val km = (distanceM ?: return null) / 1000.0
        if (km < 0.1) return null
        return (durationMin * 60.0 / km).toInt()
    }

    /** One line for the session chooser. */
    fun chooserLabel(dateText: String): String {
        val extra = mutableListOf<String>()
        paceSecPerKm()?.let { extra += "${WorkoutRecord.formatPace(it)}/km" }
        avgHr?.let { extra += "FC $it" }
        origin?.let { extra += it.substringAfterLast('.') }
        return "$dateText · ${shortText()}" + if (extra.isEmpty()) "" else " · " + extra.joinToString(" · ")
    }

    /** Short text for the notification. */
    fun shortText(): String {
        val parts = mutableListOf(typeName())
        distanceM?.let { parts += "%.2f km".format(Locale.US, it / 1000.0) }
        parts += "%.0f min".format(Locale.US, durationMin)
        return parts.joinToString(", ")
    }

    /** Text sent to Gemini instead of screenshots. Missing values are listed so the model leaves them null. */
    fun toPromptText(): String {
        val lines = mutableListOf<String>()
        lines += "Tipo: ${typeName()}${title?.let { " ($it)" } ?: ""}"
        lines += "Duración: %.1f min".format(Locale.US, durationMin)
        distanceM?.let { lines += "Distancia: %.2f km".format(Locale.US, it / 1000.0) }
        paceSecPerKm()?.let { lines += "Ritmo medio: ${WorkoutRecord.formatPace(it)} /km" }
        avgHr?.let { lines += "FC media: $it ppm" }
        maxHr?.let { lines += "FC máxima: $it ppm" }
        minHr?.let { lines += "FC mínima: $it ppm" }
        avgPowerW?.let { lines += "Potencia media: %.0f W".format(Locale.US, it) }
        maxPowerW?.let { lines += "Potencia máxima: %.0f W".format(Locale.US, it) }
        cadenceSpm?.let { lines += "Cadencia media: %.0f ppm".format(Locale.US, it) }
        maxCadenceSpm?.let { lines += "Cadencia máxima: %.0f ppm".format(Locale.US, it) }
        maxSpeedKmh?.let { lines += "Velocidad máxima: %.1f km/h".format(Locale.US, it) }
        activeKcal?.let { lines += "Calorías activas: %.0f".format(Locale.US, it) }
        totalKcal?.let { lines += "Calorías totales: %.0f".format(Locale.US, it) }
        elevationGainM?.let { lines += "Ascenso: %.0f m".format(Locale.US, it) }
        val missing = mutableListOf<String>()
        if (distanceM == null) missing += "distancia"
        if (avgHr == null) missing += "frecuencia cardíaca"
        if (avgPowerW == null) missing += "potencia"
        if (cadenceSpm == null) missing += "cadencia"
        missing += "zancada, tiempo de contacto, oscilación vertical, TE aeróbico, Body Battery y temperatura (salvo que aparezcan en capturas adjuntas)"
        lines += "No disponibles (déjalos en null, no los inventes): ${missing.joinToString(", ")}"
        return lines.joinToString("\n")
    }

    companion object {
        private const val MIN_DISTANCE_M = 500.0

        /** Latest session with real distance (a run, ride or walk); falls back to the latest of any kind. */
        fun pickBest(sessions: List<HealthSession>): HealthSession? {
            val newestFirst = sessions.sortedByDescending { it.endMillis }
            val real = newestFirst.filter { (it.distanceM ?: 0.0) >= MIN_DISTANCE_M }
            return real.firstOrNull { it.origin?.contains("garmin", ignoreCase = true) == true }
                ?: real.firstOrNull()
                ?: newestFirst.firstOrNull()
        }

        const val RUNNING = 56
        const val RUNNING_TREADMILL = 57
        const val WALKING = 79
        const val BIKING = 8
        const val BIKING_STATIONARY = 9
    }
}
