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
    val overallScore: Int,
    val headline: String,
    val sections: List<SectionReview>,
    val comparison: String,
    val suggestions: List<String>,
    val followUpQuestions: List<String>
) {
    fun paceText(): String = formatPace(paceSecPerKm)

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
        strideM?.let { parts += "zancada %.2f m".format(java.util.Locale.US, it) }
        contactMs?.let { parts += "contacto suelo $it ms" }
        verticalOscCm?.let { parts += "osc. vertical %.1f cm".format(java.util.Locale.US, it) }
        powerW?.let { parts += "potencia $it W" }
        tempC?.let { parts += "temp $it°C" }
        aerobicTe?.let { parts += "TE aeróbico %.1f".format(java.util.Locale.US, it) }
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
