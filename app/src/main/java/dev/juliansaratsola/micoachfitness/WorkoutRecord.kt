package dev.juliansaratsola.micoachfitness

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Section of the previous (graded) review format. Kept so workouts saved before "Verdad Pura" still render. */
data class SectionReview(
    val key: String,
    val title: String,
    val score: Int,
    val review: String
)

/** The five parts of the "Verdad Pura" review written by the coach. */
data class VerdadPuraReview(
    val tecnica: String = "",
    val cardioYEsfuerzo: String = "",
    val potenciaYConsistencia: String = "",
    val comparativaMeritocratica: String = "",
    val veredictoTrinchera: String = ""
) {
    val isEmpty: Boolean
        get() = listOf(tecnica, cardioYEsfuerzo, potenciaYConsistencia, comparativaMeritocratica, veredictoTrinchera)
            .all { it.isBlank() }
}

/** One analyzed workout: extracted metrics, local effort points and the coach review. Pure Kotlin so it is unit-testable. */
data class WorkoutRecord(
    val date: String,
    val type: String,
    val distanceKm: Double?,
    val durationMin: Double?,
    val paceSecPerKm: Int?,
    val avgHr: Int?,
    val maxHr: Int?,
    val cadence: Int?,
    val strideM: Double?,
    val contactMs: Int?,
    val verticalOscCm: Double?,
    val powerW: Int?,
    val tempC: Int?,
    val aerobicTe: Double?,
    val bodyBatteryImpact: Int?,
    val verticalRatioPct: Double? = null,
    val maxPowerW: Int? = null,
    val maxCadence: Int? = null,
    val ascentM: Int? = null,
    val anaerobicTe: Double? = null,
    val totalKcal: Int? = null,
    val overallScore: Int = 0,
    val headline: String = "",
    val sections: List<SectionReview> = emptyList(),
    val comparison: String = "",
    val suggestions: List<String> = emptyList(),
    val followUpQuestions: List<String> = emptyList(),
    val puntosEsfuerzo: Int = 0,
    val esDiaTrinchera: Boolean = false,
    val textoSensaciones: String = "",
    val verdadPura: VerdadPuraReview = VerdadPuraReview(),
    val tecnica: String = "",
    val cardio: String = "",
    val potenciaRendimiento: String = "",
    val comparativaPoblacional: String = "",
    val technicalMetricsWarning: Boolean = false,
    val minHr: Int? = null,
    val activeKcal: Int? = null,
    val avgSpeedKmh: Double? = null,
    val minSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val bodyWeightKg: Double? = null
) {
    fun paceText(): String = formatPace(paceSecPerKm)

    /** What the stored effort points rest on; anything but MEASURED means a standard value filled a gap. */
    fun effortBasis(): EffortBasis = EffortCalculator.basisOf(durationMin, avgHr)

    /** Secondary facts shown next to the effort points. Pace comes last: it is context, not the score. */
    fun detailParts(): List<String> {
        val us = java.util.Locale.US
        return listOfNotNull(
            durationMin?.takeIf { it > 0 }?.let { "%.0f min".format(us, it) },
            distanceKm?.takeIf { it > 0 }?.let { "%.2f km".format(us, it) },
            avgHr?.let { "FC $it" },
            paceSecPerKm?.takeIf { it > 0 }?.let { "${formatPace(it)} /km" }
        )
    }

    /** The workout day, or null when the stored date is not a valid ISO date. */
    fun localDate(): LocalDate? = parseDate(date)

    /** "3 may 2025 · Carrera": the workout day (shown as stored when it is not a valid date) and its type. */
    fun headerText(locale: Locale = Locale.getDefault()): String {
        val day = localDate()?.let { DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(it) } ?: date.trim()
        return listOf(day, type.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** True for workouts reviewed before the "Verdad Pura" format, which carry a grade and sections instead. */
    fun hasLegacyReview(): Boolean = verdadPura.isEmpty && (headline.isNotBlank() || sections.isNotEmpty())

    /** Metrics grouped for display; groups and values without data are left out. */
    fun metricGroups(includePowerPerKg: Boolean = false): List<Pair<String, List<Pair<String, String>>>> {
        val us = java.util.Locale.US
        fun group(title: String, vararg items: Pair<String, String?>) =
            title to items.mapNotNull { (label, value) -> value?.let { label to it } }
        val relativePower = if (includePowerPerKg && bodyWeightKg != null && bodyWeightKg > 0.0) {
            listOfNotNull(
                powerW?.let { "Potencia media (W/kg)" to "%.2f W/kg".format(us, it / bodyWeightKg) },
                maxPowerW?.let { "Potencia máxima (W/kg)" to "%.2f W/kg".format(us, it / bodyWeightKg) }
            )
        } else {
            emptyList()
        }
        return listOf(
            group(
                "Rendimiento",
                "Distancia" to distanceKm?.let { "%.2f km".format(us, it) },
                "Duración" to durationMin?.let { "%.0f min".format(us, it) },
                "Ritmo medio" to paceSecPerKm?.let { "${formatPace(it)} /km" },
                "Calorías totales" to totalKcal?.let { "$it kcal" },
                "Calorías activas" to activeKcal?.let { "$it kcal" }
            ),
            group(
                "Cardio",
                "FC media" to avgHr?.let { "$it ppm" },
                "FC mínima" to minHr?.let { "$it ppm" },
                "FC máxima" to maxHr?.let { "$it ppm" },
                "TE aeróbico" to aerobicTe?.let { "%.1f".format(us, it) },
                "TE anaeróbico" to anaerobicTe?.let { "%.1f".format(us, it) }
            ),
            "Potencia" to listOfNotNull(
                powerW?.let { "Potencia media" to "$it W" },
                maxPowerW?.let { "Potencia máxima" to "$it W" }
            ) + relativePower,
            group(
                "Velocidad",
                "Velocidad media" to avgSpeedKmh?.let { "%.1f km/h".format(us, it) },
                "Velocidad mínima" to minSpeedKmh?.let { "%.1f km/h".format(us, it) },
                "Velocidad máxima" to maxSpeedKmh?.let { "%.1f km/h".format(us, it) }
            ),
            group(
                "Dinámica de carrera",
                "Cadencia media" to cadence?.let { "$it ppm" },
                "Cadencia máxima" to maxCadence?.let { "$it ppm" },
                "Longitud de zancada" to strideM?.let { "%.2f m".format(us, it) },
                "Tiempo de contacto" to contactMs?.let { "$it ms" },
                "Oscilación vertical" to verticalOscCm?.let { "%.1f cm".format(us, it) },
                "Ratio vertical" to verticalRatioPct?.let { "%.1f %%".format(us, it) }
            ),
            group(
                "Entorno y recuperación",
                "Ascenso total" to ascentM?.let { "$it m" },
                "Temperatura" to tempC?.let { "$it °C" },
                "Body Battery" to bodyBatteryImpact?.let { "$it" }
            )
        ).filter { it.second.isNotEmpty() }
    }

    /** Compact single line used as history context in the next prompt: effort first, pace is just one more detail. */
    fun toHistoryLine(): String {
        val us = java.util.Locale.US
        val parts = mutableListOf<String>()
        parts += date
        parts += type
        durationMin?.let { parts += "%.0f min".format(us, it) }
        distanceKm?.let { parts += "%.2f km".format(us, it) }
        parts += "esfuerzo $puntosEsfuerzo pts" + when (effortBasis()) {
            EffortBasis.MEASURED -> ""
            EffortBasis.NO_HEART_RATE -> " (estimado, sin FC)"
            EffortBasis.NO_DURATION -> " (estimado, sin duración)"
            EffortBasis.NO_DATA -> " (estimado, sin FC ni duración)"
        }
        if (esDiaTrinchera) parts += "DÍA DE TRINCHERA"
        avgHr?.let { parts += "FC media $it" }
        maxHr?.let { parts += "FC max $it" }
        minHr?.let { parts += "FC min $it" }
        paceSecPerKm?.let { parts += "ritmo ${formatPace(it)}/km" }
        avgSpeedKmh?.let { parts += "velocidad media %.1f km/h".format(us, it) }
        minSpeedKmh?.let { parts += "velocidad mínima %.1f km/h".format(us, it) }
        maxSpeedKmh?.let { parts += "velocidad máxima %.1f km/h".format(us, it) }
        cadence?.let { parts += "cadencia $it" }
        maxCadence?.let { parts += "cadencia máx. $it" }
        strideM?.let { parts += "zancada %.2f m".format(us, it) }
        contactMs?.let { parts += "contacto suelo $it ms" }
        verticalOscCm?.let { parts += "osc. vertical %.1f cm".format(us, it) }
        verticalRatioPct?.let { parts += "ratio vertical %.1f%%".format(us, it) }
        powerW?.let { parts += "potencia $it W" }
        maxPowerW?.let { parts += "potencia máx. $it W" }
        ascentM?.let { parts += "ascenso $it m" }
        tempC?.let { parts += "temp $it°C" }
        aerobicTe?.let { parts += "TE aeróbico %.1f".format(us, it) }
        anaerobicTe?.let { parts += "TE anaeróbico %.1f".format(us, it) }
        totalKcal?.let { parts += "$it kcal" }
        activeKcal?.let { parts += "$it kcal activas" }
        bodyBatteryImpact?.let { parts += "Body Battery $it" }
        feelingsForPrompt()?.let { parts += "sensaciones: \"$it\"" }
        return parts.joinToString(" | ")
    }

    /** The user's own words, collapsed to one line and capped so a long note cannot flood the prompt. */
    fun feelingsForPrompt(): String? {
        val text = textoSensaciones.trim().replace(Regex("\\s+"), " ").replace('"', '\'')
        if (text.isEmpty()) return null
        return if (text.length > MAX_PROMPT_FEELINGS) text.take(MAX_PROMPT_FEELINGS - 1).trimEnd() + "…" else text
    }

    companion object {
        private const val MAX_PROMPT_FEELINGS = 200
        const val MIN_BODY_WEIGHT_KG = 20.0
        const val MAX_BODY_WEIGHT_KG = 300.0

        /** Parses a yyyy-MM-dd date; null when the text is not a valid ISO date. */
        fun parseDate(text: String?): LocalDate? = try {
            text?.let { LocalDate.parse(it.trim()) }
        } catch (error: DateTimeParseException) {
            null
        }

        fun formatPace(secPerKm: Int?): String {
            if (secPerKm == null || secPerKm <= 0) return "-"
            return "%d:%02d".format(secPerKm / 60, secPerKm % 60)
        }
    }
}
