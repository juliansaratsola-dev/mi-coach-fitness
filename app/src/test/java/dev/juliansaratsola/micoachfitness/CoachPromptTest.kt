package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachPromptTest {
    private fun record(date: String, pace: Int, hr: Int, score: Int) = WorkoutRecord(
        date = date, type = "Carrera", distanceKm = 7.91, durationMin = 41.0,
        paceSecPerKm = pace, avgHr = hr, maxHr = 171, cadence = 180, strideM = 1.07,
        contactMs = 245, verticalOscCm = 8.5, powerW = 288, tempC = 26, aerobicTe = 3.0,
        bodyBatteryImpact = -7, overallScore = score, headline = "ok",
        sections = listOf(SectionReview("cardio", "Cardio", score, "bien")),
        comparison = "", suggestions = emptyList(), followUpQuestions = emptyList()
    )

    @Test
    fun formatsPace() {
        assertEquals("5:12", WorkoutRecord.formatPace(312))
        assertEquals("-", WorkoutRecord.formatPace(null))
    }

    @Test
    fun emptyHistoryIsStated() {
        val prompt = CoachPrompt.userPrompt(emptyList(), "2026-10-31")
        assertTrue(prompt.contains("sin entrenamientos previos"))
        assertNull(CoachPrompt.trendSummary(emptyList()))
    }

    @Test
    fun historyAndTrendsAreIncluded() {
        val history = listOf(record("2026-10-17", 298, 157, 8), record("2026-10-19", 312, 157, 9))
        val prompt = CoachPrompt.userPrompt(history, "2026-10-31", "tibias bien")
        assertTrue(prompt.contains("2026-10-19"))
        assertTrue(prompt.contains("ritmo 5:12/km"))
        assertTrue(prompt.contains("cardio=9"))
        assertTrue(prompt.contains("ritmo medio 5:05/km"))
        assertTrue(prompt.contains("tibias bien"))
        assertNotNull(CoachPrompt.trendSummary(history))
    }

    @Test
    fun historyIsLimited() {
        val history = (1..20).map { record("2026-09-%02d".format(it), 300, 150, 7) }
        val prompt = CoachPrompt.userPrompt(history, "2026-10-31")
        assertEquals(CoachPrompt.HISTORY_LIMIT, prompt.lines().count { it.startsWith("- ") })
    }
}
