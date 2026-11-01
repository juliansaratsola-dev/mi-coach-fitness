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
        cadenceSpm = null,
        origin = HealthSession.GARMIN_PACKAGE
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
    fun promptTextIncludesAvailableHealthConnectSpeedMetrics() {
        val text = session.copy(
            avgSpeedKmh = 10.8,
            minSpeedKmh = 2.4,
            maxSpeedKmh = 13.7
        ).toPromptText()
        assertTrue(text.contains("Velocidad media registrada: 10.8 km/h"))
        assertTrue(text.contains("Velocidad mínima registrada: 2.4 km/h"))
        assertTrue(text.contains("Velocidad máxima: 13.7 km/h"))
    }

    @Test
    fun pickBestIgnoresSessionsFromOtherApps() {
        val phone = session.copy(startMillis = 3_000_000L, endMillis = 4_300_000L, distanceM = 900.0, origin = "com.google.android.apps.fitness")
        assertEquals(session, HealthSession.pickBest(listOf(phone, session)))
    }

    @Test
    fun pickBestReturnsNullWithoutGarminSessions() {
        val phone = session.copy(origin = "com.google.android.apps.fitness")
        assertEquals(null, HealthSession.pickBest(listOf(phone, session.copy(origin = null))))
    }
}
