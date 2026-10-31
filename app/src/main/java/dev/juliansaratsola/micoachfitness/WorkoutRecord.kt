package dev.juliansaratsola.micoachfitness

data class SectionReview(
    val key: String,
    val title: String,
    val score: Int,
    val review: String
)

/** One analyzed workout: extracted metrics plus the coach review. Pure Kotlin so it is unit-testable. */
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
    val overallScore: Int,
    val headline: String,
    val sections: List<SectionReview>,
    val comparison: String,
    val suggestions: List<String>,
    val followUpQuestions: List<String>
) {
    fun paceText(): String = formatPace(paceSecPerKm)

    /** Metrics grouped for display; groups and values without data are left out. */
    fun metricGroups(): List<Pair<String, List<Pair<String, String>>>> {
        val us = java.util.Locale.US
        fun group(title: String, vararg items: Pair<String, String?>) =
            title to items.mapNotNull { (label, value) -> value?.let { label to it } }
        return listOf(
            group(
                "Rendimiento",
                "Distancia" to distanceKm?.let { "%.2f km".format(us, it) },
                "Duración" to durationMin?.let { "%.0f min".format(us, it) },
                "Ritmo medio" to paceSecPerKm?.let { "${formatPace(it)} /km" },
                "Calorías" to totalKcal?.let { "$it kcal" }
            ),
            group(
                "Cardio",
                "FC media" to avgHr?.let { "$it ppm" },
                "FC máxima" to maxHr?.let { "$it ppm" },
                "TE aeróbico" to aerobicTe?.let { "%.1f".format(us, it) },
                "TE anaeróbico" to anaerobicTe?.let { "%.1f".format(us, it) }
            ),
            group(
                "Potencia",
                "Potencia media" to powerW?.let { "$it W" },
                "Potencia máxima" to maxPowerW?.let { "$it W" }
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

    /** Compact single line used as history context in the next prompt. */
    fun toHistoryLine(): String {
        val parts = mutableListOf<String>()
        parts += date
        parts += type
        distanceKm?.let { parts += "%.2f km".format(java.util.Locale.US, it) }
        durationMin?.let { parts += "%.0f min".format(java.util.Locale.US, it) }
        paceSecPerKm?.let { parts += "ritmo ${formatPace(it)}/km" }
        avgHr?.let { parts += "FC media $it" }
        maxHr?.let { parts += "FC max $it" }
        cadence?.let { parts += "cadencia $it" }
        maxCadence?.let { parts += "cadencia máx. $it" }
        strideM?.let { parts += "zancada %.2f m".format(java.util.Locale.US, it) }
        contactMs?.let { parts += "contacto suelo $it ms" }
        verticalOscCm?.let { parts += "osc. vertical %.1f cm".format(java.util.Locale.US, it) }
        verticalRatioPct?.let { parts += "ratio vertical %.1f%%".format(java.util.Locale.US, it) }
        powerW?.let { parts += "potencia $it W" }
        maxPowerW?.let { parts += "potencia máx. $it W" }
        ascentM?.let { parts += "ascenso $it m" }
        tempC?.let { parts += "temp $it°C" }
        aerobicTe?.let { parts += "TE aeróbico %.1f".format(java.util.Locale.US, it) }
        anaerobicTe?.let { parts += "TE anaeróbico %.1f".format(java.util.Locale.US, it) }
        totalKcal?.let { parts += "$it kcal" }
        bodyBatteryImpact?.let { parts += "Body Battery $it" }
        val scores = sections.joinToString(", ") { "${it.key}=${it.score}" }
        parts += "notas: general=$overallScore" + if (scores.isNotEmpty()) ", $scores" else ""
        return parts.joinToString(" | ")
    }

    companion object {
        fun formatPace(secPerKm: Int?): String {
            if (secPerKm == null || secPerKm <= 0) return "-"
            return "%d:%02d".format(secPerKm / 60, secPerKm % 60)
        }
    }
}
