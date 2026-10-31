package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSessionTest {
    private val session = HealthSession(
        startMillis = 0L,
        endMillis = 2_469_000L,
        exerciseType = HealthSession.RUNNING,
        title = null,
        distanceM = 7910.0,
        avgHr = 157,
        maxHr = 171,
        avgPowerW = null,
        elevationGainM = 8.0,
        cadenceSpm = null
    )

    @Test
    fun computesPaceFromDistanceAndDuration() {
        assertEquals("5:12", WorkoutRecord.formatPace(session.paceSecPerKm()))
    }

    @Test
    fun promptTextListsMissingMetrics() {
        val text = session.toPromptText()
        assertTrue(text.contains("FC media: 157 ppm"))
        assertTrue(text.contains("potencia"))
        assertTrue(text.contains("no los inventes"))
    }

    @Test
    fun pickBestSkipsTinyLaterSession() {
        val tiny = session.copy(startMillis = 3_000_000L, endMillis = 4_300_000L, exerciseType = 0, distanceM = 90.0)
        assertEquals(session, HealthSession.pickBest(listOf(session, tiny)))
    }

    @Test
    fun pickBestFallsBackToLatest() {
        val tiny = session.copy(endMillis = 5_000_000L, distanceM = null)
        assertEquals(tiny, HealthSession.pickBest(listOf(tiny)))
    }
}
