package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WorkoutRecordTest {
    private fun record(
        date: String = "2026-10-30",
        avgHr: Int? = 157,
        points: Int = 90,
        trench: Boolean = false,
        feelings: String = "",
        durationMin: Double? = 41.0
    ) = WorkoutRecord(
        date = date, type = "Carrera", distanceKm = 7.91, durationMin = durationMin, paceSecPerKm = 312,
        avgHr = avgHr, maxHr = 171, cadence = 180, strideM = null, contactMs = null, verticalOscCm = null,
        powerW = null, tempC = null, aerobicTe = null, bodyBatteryImpact = null,
        puntosEsfuerzo = points, esDiaTrinchera = trench, textoSensaciones = feelings
    )

    @Test
    fun historyLineLeadsWithEffortAndKeepsPaceAsADetail() {
        val line = record().toHistoryLine()
        assertTrue(line.startsWith("2026-10-30 | Carrera | 41 min | 7.91 km | esfuerzo 90 pts"))
        assertTrue(line.contains("ritmo 5:12/km"))
        assertTrue(line.indexOf("esfuerzo") < line.indexOf("ritmo"))
        assertFalse(line.contains("trinchera", ignoreCase = true))
        assertFalse(line.contains("estimado"))
    }

    @Test
    fun historyLineMarksTrenchDaysAndEstimatedEffort() {
        assertTrue(record(trench = true).toHistoryLine().contains("DÍA DE TRINCHERA"))
        assertTrue(record(avgHr = null).toHistoryLine().contains("esfuerzo 90 pts (estimado, sin FC)"))
        assertTrue(record(durationMin = null).toHistoryLine().contains("esfuerzo 90 pts (estimado, sin duración)"))
        assertTrue(
            record(avgHr = null, durationMin = null).toHistoryLine()
                .contains("esfuerzo 90 pts (estimado, sin FC ni duración)")
        )
    }

    @Test
    fun effortBasisFollowsTheDataTheCalculatorCanUse() {
        assertEquals(EffortBasis.MEASURED, record().effortBasis())
        assertEquals(EffortBasis.NO_HEART_RATE, record(avgHr = null).effortBasis())
        assertEquals(EffortBasis.NO_HEART_RATE, record(avgHr = 12).effortBasis())
        assertEquals(EffortBasis.NO_DURATION, record(durationMin = null).effortBasis())
        assertEquals(EffortBasis.NO_DURATION, record(durationMin = 0.0).effortBasis())
        assertEquals(EffortBasis.NO_DATA, record(avgHr = null, durationMin = null).effortBasis())
    }

    @Test
    fun detailPartsKeepPaceLastAndSkipMissingData() {
        assertEquals(listOf("41 min", "7.91 km", "FC 157", "5:12 /km"), record().detailParts())
        assertEquals(listOf("41 min", "7.91 km"), record(avgHr = null).copy(paceSecPerKm = null).detailParts())
        assertEquals(emptyList<String>(), record(avgHr = null, durationMin = null).copy(distanceKm = null, paceSecPerKm = null).detailParts())
        assertEquals(listOf("FC 157"), record(durationMin = 0.0).copy(distanceKm = 0.0, paceSecPerKm = 0).detailParts())
    }

    @Test
    fun feelingsAreSanitizedForThePrompt() {
        val line = record(feelings = "  Piernas  \"pesadas\"\ny mucho calor ").toHistoryLine()
        assertTrue(line.contains("sensaciones: \"Piernas 'pesadas' y mucho calor\""))
        assertNull(record(feelings = "   ").feelingsForPrompt())
    }

    @Test
    fun longFeelingsAreCappedInThePrompt() {
        val text = record(feelings = "a".repeat(500)).feelingsForPrompt()!!
        assertEquals(200, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun localDateIsNullWhenTheDateIsNotIso() {
        assertEquals(LocalDate.of(2026, 10, 30), record().localDate())
        assertNull(record(date = "30/10/2026").localDate())
        assertNull(record(date = "").localDate())
    }

    @Test
    fun headerTextShowsTheDayAndTheType() {
        assertEquals("30 Oct 2026 · Carrera", record().headerText(Locale.US))
        assertEquals("30 Oct 2026", record().copy(type = "  ").headerText(Locale.US))
        assertEquals("30/10/2026 · Carrera", record(date = "30/10/2026").headerText(Locale.US))
        assertEquals("Carrera", record(date = "").headerText(Locale.US))
    }

    @Test
    fun legacyReviewIsDetectedOnlyWithoutTheNewReview() {
        val legacy = record().copy(headline = "Bien", sections = listOf(SectionReview("cardio", "Cardio", 8, "ok")))
        assertTrue(legacy.hasLegacyReview())
        assertFalse(legacy.copy(verdadPura = VerdadPuraReview(veredictoTrinchera = "Dura")).hasLegacyReview())
        assertFalse(record().hasLegacyReview())
    }
}
