package dev.juliansaratsola.micoachfitness

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and enriches the JSON of a stored workout. It only needs org.json, so it stays unit-testable on the JVM
 * (WorkoutStore itself needs the Android framework).
 */
object WorkoutJson {
    const val KEY_EFFORT_POINTS = "puntos_esfuerzo"
    const val KEY_TRENCH_DAY = "es_dia_trinchera"
    const val KEY_FEELINGS = "texto_sensaciones"

    const val KEY_TECHNIQUE = "tecnica"
    const val KEY_CARDIO = "cardio_y_esfuerzo"
    const val KEY_POWER = "potencia_y_consistencia"
    const val KEY_COMPARISON = "comparativa_meritocratica"
    const val KEY_VERDICT = "veredicto_trinchera"

    const val MAX_FEELINGS_CHARS = 500

    /**
     * Builds a record from stored JSON. Workouts saved before the effort model have no `puntos_esfuerzo`, so it is
     * derived from their metrics; [userAge] only matters for that backfill.
     */
    fun parse(json: JSONObject, userAge: Int? = null): WorkoutRecord {
        val metrics = json.optJSONObject("metrics") ?: JSONObject()
        val sections = json.optJSONArray("sections") ?: JSONArray()
        val durationMin = metrics.num("durationMin")
        val paceSecPerKm = metrics.num("paceSecPerKm")?.toInt()
        val avgHr = metrics.num("avgHr")?.toInt()
        val maxHr = metrics.num("maxHr")?.toInt()
        val feelings = json.str(KEY_FEELINGS)
        val storedPoints = if (json.has(KEY_EFFORT_POINTS) && !json.isNull(KEY_EFFORT_POINTS)) {
            json.optInt(KEY_EFFORT_POINTS, 0).coerceAtLeast(0)
        } else {
            null
        }
        val backfilled = if (storedPoints == null) {
            EffortCalculator.evaluateSession(durationMin, avgHr, maxHr, userAge, feelings, paceSecPerKm)
        } else {
            null
        }
        return WorkoutRecord(
            date = json.str("date"),
            type = json.str("type", "Carrera"),
            distanceKm = metrics.num("distanceKm"),
            durationMin = durationMin,
            paceSecPerKm = paceSecPerKm,
            avgHr = avgHr,
            maxHr = maxHr,
            cadence = metrics.num("cadence")?.toInt(),
            strideM = metrics.num("strideM"),
            contactMs = metrics.num("contactMs")?.toInt(),
            verticalOscCm = metrics.num("verticalOscCm"),
            powerW = metrics.num("powerW")?.toInt(),
            tempC = metrics.num("tempC")?.toInt(),
            aerobicTe = metrics.num("aerobicTe"),
            bodyBatteryImpact = metrics.num("bodyBatteryImpact")?.toInt(),
            verticalRatioPct = metrics.num("verticalRatioPct"),
            maxPowerW = metrics.num("maxPowerW")?.toInt(),
            maxCadence = metrics.num("maxCadence")?.toInt(),
            ascentM = metrics.num("ascentM")?.toInt(),
            anaerobicTe = metrics.num("anaerobicTe"),
            totalKcal = metrics.num("totalKcal")?.toInt(),
            overallScore = json.optInt("overallScore", 0).coerceIn(0, 10),
            headline = json.str("headline"),
            sections = (0 until sections.length()).mapNotNull { i ->
                sections.optJSONObject(i)?.let {
                    SectionReview(
                        key = it.str("key"),
                        title = it.str("title"),
                        score = it.optInt("score", 0).coerceIn(0, 10),
                        review = it.str("review")
                    )
                }
            },
            comparison = json.str("comparison"),
            suggestions = json.optJSONArray("suggestions").strings(),
            followUpQuestions = json.optJSONArray("followUpQuestions").strings(),
            puntosEsfuerzo = storedPoints ?: backfilled?.points ?: 0,
            esDiaTrinchera = if (storedPoints != null) json.optBoolean(KEY_TRENCH_DAY, false) else backfilled?.isTrenchDay == true,
            textoSensaciones = feelings,
            verdadPura = reviewOf(json)
        )
    }

    /** The five "Verdad Pura" texts of a Gemini response (or stored workout); missing ones become empty. */
    fun reviewOf(json: JSONObject): VerdadPuraReview = VerdadPuraReview(
        tecnica = json.str(KEY_TECHNIQUE).trim(),
        cardioYEsfuerzo = json.str(KEY_CARDIO).trim(),
        potenciaYConsistencia = json.str(KEY_POWER).trim(),
        comparativaMeritocratica = json.str(KEY_COMPARISON).trim(),
        veredictoTrinchera = json.str(KEY_VERDICT).trim()
    )

    /**
     * Makes sure [raw] has a usable workout date. A valid ISO date that is not after [fallbackDate] is kept; anything
     * else (missing, malformed, or a future date misread from a screenshot) becomes [fallbackDate], so the workout
     * still counts towards the Form Score.
     */
    fun normalizeDate(raw: JSONObject, fallbackDate: String): JSONObject {
        val date = WorkoutRecord.parseDate(raw.str("date"))
        val latest = WorkoutRecord.parseDate(fallbackDate)
        val valid = date?.takeIf { latest == null || !it.isAfter(latest) }
        raw.put("date", valid?.toString() ?: fallbackDate)
        return raw
    }

    /**
     * Computes the effort of a freshly reviewed workout from its extracted metrics and the user's feelings, and
     * writes `puntos_esfuerzo`, `es_dia_trinchera` and `texto_sensaciones` into [raw] so they are persisted with it.
     */
    fun applyEffort(raw: JSONObject, notes: String?, userAge: Int?): JSONObject {
        val metrics = raw.optJSONObject("metrics") ?: JSONObject()
        val feelings = cleanFeelings(notes)
        val effort = EffortCalculator.evaluateSession(
            durationMinutes = metrics.num("durationMin"),
            avgHR = metrics.num("avgHr")?.toInt(),
            maxHR = metrics.num("maxHr")?.toInt(),
            userAge = userAge,
            rpeText = feelings,
            avgPace = metrics.num("paceSecPerKm")?.toInt()
        )
        raw.put(KEY_EFFORT_POINTS, effort.points)
        raw.put(KEY_TRENCH_DAY, effort.isTrenchDay)
        raw.put(KEY_FEELINGS, feelings)
        return raw
    }

    /**
     * Parses the text Gemini returned for a review. Tolerates a markdown code fence or text around the JSON object;
     * throws [org.json.JSONException] when there is no valid JSON object.
     */
    fun parseReviewResponse(text: String): JSONObject {
        var body = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        if (!body.startsWith("{")) {
            val start = body.indexOf('{')
            val end = body.lastIndexOf('}')
            if (start >= 0 && end > start) body = body.substring(start, end + 1)
        }
        return JSONObject(body)
    }

    /** True when the response carries at least one of the five review texts. */
    fun hasReview(json: JSONObject): Boolean = !reviewOf(json).isEmpty

    private fun cleanFeelings(notes: String?): String =
        notes.orEmpty().trim().replace(Regex("[ \\t]*\\r?\\n[ \\t]*"), "\n").take(MAX_FEELINGS_CHARS)

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    // Android's optString returns the text "null" for a JSON null, so nulls are checked explicitly.
    private fun JSONObject.str(key: String, default: String = ""): String =
        if (!has(key) || isNull(key)) default else optString(key, default)

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).filter { !isNull(it) }.map { optString(it) }.filter { it.isNotBlank() }
    }
}
