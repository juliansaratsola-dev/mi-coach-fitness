package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoricalContextTest {
    private val today = LocalDate.of(2026, 10, 31)

    private fun record(
        daysAgo: Int,
        points: Int = 80,
        pace: Int? = 312,
        trench: Boolean = false,
        date: String? = null
    ) = WorkoutRecord(
        date = date ?: today.minusDays(daysAgo.toLong()).toString(), type = "Carrera", distanceKm = 7.9,
        durationMin = 41.0, paceSecPerKm = pace, avgHr = 157, maxHr = 171, cadence = 180, strideM = null,
        contactMs = 245, verticalOscCm = null, powerW = null, tempC = null, aerobicTe = null,
        bodyBatteryImpact = null, puntosEsfuerzo = points, esDiaTrinchera = trench
    )

    private fun historyLines(text: String) = text.lines().filter { it.startsWith("- ") }

    @Test
    fun emptyHistoryStatesThereAreNoPreviousWorkouts() {
        val context = HistoricalContext.build(emptyList(), today)
        assertTrue(context.contains("FORM SCORE ACTUAL: 0 (nivel 1, Oxidado)"))
        assertTrue(context.contains("sin entrenamientos previos"))
        assertTrue(historyLines(context).isEmpty())
        assertNull(HistoricalContext.trendSummary(emptyList()))
    }

    @Test
    fun formScoreAndGravityAreExplained() {
        val context = HistoricalContext.build(listOf(record(3, points = 100)), today)
        assertTrue(context.contains("FORM SCORE ACTUAL: 25 (nivel 2, Recluta)"))
        assertTrue(context.contains("100 pts en 1 día entrenado"))
        assertTrue(context.contains("último entreno hace 3 días"))
        assertTrue(context.contains("divide esa suma entre 4"))
    }

    @Test
    fun trainingTodayIsStatedAsToday() {
        val context = HistoricalContext.build(listOf(record(0, points = 90)), today)
        assertTrue(context.contains("último entreno hoy"))
        assertTrue(context.contains("divide esa suma entre 1"))
    }

    @Test
    fun onlyTheLastFiveWorkoutsAreListed() {
        val history = (1..20).map { record(daysAgo = it) }
        val context = HistoricalContext.build(history, today)
        assertEquals(HistoricalContext.SESSION_LIMIT, historyLines(context).size)
        assertTrue(context.contains("ÚLTIMOS 5 ENTRENOS"))
    }

    @Test
    fun theLimitCanBeRaisedForTheChat() {
        val history = (1..20).map { record(daysAgo = it) }
        assertEquals(8, historyLines(HistoricalContext.build(history, today, limit = 8)).size)
    }

    @Test
    fun latestWorkoutsAreChosenByDateNotByInsertionOrder() {
        val history = listOf(
            record(daysAgo = 1), record(daysAgo = 30), record(daysAgo = 2),
            record(daysAgo = 0, date = "sin-fecha"), record(daysAgo = 10)
        )
        val latest = HistoricalContext.latest(history, 3)
        assertEquals(
            listOf(today.minusDays(10).toString(), today.minusDays(2).toString(), today.minusDays(1).toString()),
            latest.map { it.date }
        )
    }

    @Test
    fun aWorkoutWithAnInvalidDateIsTheFirstToBeDropped() {
        val history = listOf(record(daysAgo = 0, date = "sin-fecha"), record(daysAgo = 5), record(daysAgo = 4))
        assertEquals(listOf(today.minusDays(5).toString(), today.minusDays(4).toString()),
            HistoricalContext.latest(history, 2).map { it.date })
    }

    @Test
    fun historyLinesPutEffortBeforePace() {
        val line = historyLines(HistoricalContext.build(listOf(record(1, points = 120, trench = true)), today)).single()
        assertTrue(line.contains("esfuerzo 120 pts"))
        assertTrue(line.contains("DÍA DE TRINCHERA"))
        assertTrue(line.indexOf("esfuerzo") < line.indexOf("ritmo"))
    }

    @Test
    fun trendSummaryLeadsWithEffortAndMarksPaceAsSecondary() {
        val summary = HistoricalContext.trendSummary(listOf(record(3, points = 60, pace = 298), record(1, points = 100, pace = 312)))
        assertNotNull(summary)
        assertTrue(summary!!.contains("últimas 2 sesiones"))
        assertTrue(summary.contains("esfuerzo medio 80 pts"))
        assertTrue(summary.contains("ritmo medio 5:05/km (dato secundario)"))
        assertTrue(summary.indexOf("esfuerzo medio") < summary.indexOf("ritmo medio"))
        assertFalse(summary.contains("nota general"))
    }

    @Test
    fun trendSummaryCountsTrenchDays() {
        val summary = HistoricalContext.trendSummary(listOf(record(3, trench = true), record(2), record(1, trench = true)))
        assertTrue(summary!!.contains("días de trinchera: 2 de 3"))
        assertFalse(HistoricalContext.trendSummary(listOf(record(1)))!!.contains("trinchera"))
    }

    @Test
    fun trendSummaryOmitsPaceWhenNoneIsKnown() {
        val summary = HistoricalContext.trendSummary(listOf(record(2, pace = null), record(1, pace = null)))
        assertFalse(summary!!.contains("ritmo medio"))
    }
}
