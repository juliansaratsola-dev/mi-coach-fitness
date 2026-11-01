package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Persists analyzed workouts as JSON in app-private storage; this is the coach's memory. */
class WorkoutStore(context: Context) {
    private val prefs = MonitorPreferences(context)
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    @Synchronized
    fun all(): List<WorkoutRecord> {
        val array = readArray()
        val age = prefs.userAge
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { WorkoutJson.parse(it, age) }
        }
    }

    @Synchronized
    fun latestAnalysis(): JSONObject? {
        val array = readArray()
        if (array.length() == 0) return null
        return JSONObject(array.getJSONObject(array.length() - 1).toString())
    }

    /**
     * Saves a reviewed workout. Its effort points, trench-day flag and the user's [notes] ("sensaciones") are
     * computed here and stored with it, so the history keeps the score it had when it was recorded.
     */
    @Synchronized
    fun add(raw: JSONObject, fallbackDate: String, notes: String? = null): WorkoutRecord {
        val age = prefs.userAge
        WorkoutJson.normalizeDate(raw, fallbackDate)
        WorkoutJson.applyEffort(raw, notes, age)
        val record = WorkoutJson.parse(raw, age)
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

    /** "Gravity" Form Score as of [today]: 30-day effort divided by (days since the last workout + 1). */
    fun calculateFormScore(today: LocalDate = LocalDate.now()): Double = FormScore.calculate(all(), today)

    /** Hidden prompt context: the current Form Score plus a summary of the last five workouts. */
    fun getHistoricalContextForAI(today: LocalDate = LocalDate.now()): String =
        HistoricalContext.build(all(), today)

    private fun readArray(): JSONArray {
        if (!file.baseFile.exists()) return JSONArray()
        return JSONArray(file.openRead().bufferedReader().use { it.readText() })
    }

    companion object {
        private const val FILE_NAME = "workouts.json"
    }
}
