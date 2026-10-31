package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Persists analyzed workouts as JSON in app-private storage; this is the coach's memory. */
class WorkoutStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    @Synchronized
    fun all(): List<WorkoutRecord> {
        val array = readArray()
        return (0 until array.length()).map { index ->
            parse(array.getJSONObject(index))
        }
    }

    @Synchronized
    fun add(raw: JSONObject, fallbackDate: String): WorkoutRecord {
        if (raw.isNull("date") || raw.optString("date").isBlank()) raw.put("date", fallbackDate)
        val record = parse(raw)
        val array = readArray()
        array.put(raw)
        val output = file.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw error
        }
        return record
    }

    private fun readArray(): JSONArray {
        if (!file.baseFile.exists()) return JSONArray()
        return JSONArray(file.openRead().bufferedReader().use { it.readText() })
    }

    companion object {
        private const val FILE_NAME = "workouts.json"

        fun parse(json: JSONObject): WorkoutRecord {
            val metrics = json.optJSONObject("metrics") ?: JSONObject()
            val sections = json.optJSONArray("sections") ?: JSONArray()
            return WorkoutRecord(
                date = json.optString("date"),
                type = json.optString("type", "Carrera"),
                distanceKm = metrics.num("distanceKm"),
                durationMin = metrics.num("durationMin"),
                paceSecPerKm = metrics.num("paceSecPerKm")?.toInt(),
                avgHr = metrics.num("avgHr")?.toInt(),
                maxHr = metrics.num("maxHr")?.toInt(),
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
                headline = json.optString("headline"),
                sections = (0 until sections.length()).mapNotNull { i ->
                    sections.optJSONObject(i)?.let {
                        SectionReview(
                            key = it.optString("key"),
                            title = it.optString("title"),
                            score = it.optInt("score", 0).coerceIn(0, 10),
                            review = it.optString("review")
                        )
                    }
                },
                comparison = json.optString("comparison"),
                suggestions = json.optJSONArray("suggestions").strings(),
                followUpQuestions = json.optJSONArray("followUpQuestions").strings()
            )
        }

        private fun JSONObject.num(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val value = optDouble(key, Double.NaN)
            return if (value.isNaN()) null else value
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
        }
    }
}
