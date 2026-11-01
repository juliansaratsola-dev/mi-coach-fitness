package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConsistencyChartLayoutTest {
    private val start = LocalDate.of(2026, 5, 2)

    private fun day(index: Int, points: Int = 0, sessions: Int = if (points > 0) 1 else 0, trench: Boolean = false) =
        DayEffort(start.plusDays(index.toLong()), points, trench, sessions)

    private fun days(vararg points: Int) = points.mapIndexed { index, p -> day(index, p) }

    @Test
    fun barHeightsAreProportionalToTheEffort() {
        val bars = ConsistencyChartLayout.bars(days(50, 100, 25), scale = 100, width = 90f, height = 80f)
        assertEquals(40f, bars[0].fillHeight, 1e-4f)
        assertEquals(80f, bars[1].fillHeight, 1e-4f)
        assertEquals(20f, bars[2].fillHeight, 1e-4f)
        assertEquals(bars[1].fillHeight, 2 * bars[0].fillHeight, 1e-4f)
    }

    @Test
    fun restDaysStayEmpty() {
        val bars = ConsistencyChartLayout.bars(days(0, 60), scale = 60, width = 20f, height = 50f, minFill = 4f)
        assertFalse(bars[0].trained)
        assertEquals(0f, bars[0].fillHeight, 0f)
        assertTrue(bars[1].trained)
    }

    @Test
    fun aTrainedDayAlwaysKeepsAVisibleStub() {
        val tiny = listOf(day(0, points = 1), day(1, points = 0, sessions = 1))
        val bars = ConsistencyChartLayout.bars(tiny, scale = 200, width = 20f, height = 100f, minFill = 4f)
        assertEquals(4f, bars[0].fillHeight, 1e-4f)
        assertEquals(4f, bars[1].fillHeight, 1e-4f)
        assertTrue(bars[1].trained)
    }

    @Test
    fun barsNeverExceedTheChartHeight() {
        val bars = ConsistencyChartLayout.bars(days(500), scale = 100, width = 10f, height = 60f)
        assertEquals(60f, bars[0].fillHeight, 1e-4f)
        assertEquals(0f, bars[0].fillTop, 1e-4f)
        assertEquals(60f, bars[0].bottom, 0f)
    }

    @Test
    fun aMinimumLargerThanTheChartIsClamped() {
        val bars = ConsistencyChartLayout.bars(days(1), scale = 100, width = 10f, height = 20f, minFill = 99f)
        assertEquals(20f, bars[0].fillHeight, 1e-4f)
    }

    @Test
    fun slotsAreEvenlySpacedInsideTheWidthWithoutOverlap() {
        val all = (0 until FormScore.WINDOW_DAYS).map { day(it, points = 10 + it) }
        val bars = ConsistencyChartLayout.bars(all, scale = 100, width = 300f, height = 50f)
        assertEquals(30, bars.size)
        assertTrue(bars.first().left > 0f)
        assertTrue(bars.last().right < 300f)
        val gaps = bars.zipWithNext { a, b -> b.left - a.right }
        assertTrue(gaps.all { it > 0f })
        val widths = bars.map { it.right - it.left }
        assertTrue(widths.all { Math.abs(it - widths[0]) < 1e-4f })
        val steps = bars.zipWithNext { a, b -> b.left - a.left }
        assertTrue(steps.all { Math.abs(it - 10f) < 1e-3f })
    }

    @Test
    fun onlyTheLastSlotIsToday() {
        val bars = ConsistencyChartLayout.bars(days(10, 0, 30), scale = 30, width = 30f, height = 30f)
        assertEquals(listOf(false, false, true), bars.map { it.isToday })
    }

    @Test
    fun trenchDaysAreCarriedToTheBars() {
        val input = listOf(day(0, 80, trench = true), day(1, 80))
        val bars = ConsistencyChartLayout.bars(input, scale = 100, width = 20f, height = 40f)
        assertEquals(listOf(true, false), bars.map { it.isTrenchDay })
    }

    @Test
    fun nothingIsDrawnWithoutDaysOrRoom() {
        assertTrue(ConsistencyChartLayout.bars(emptyList(), 100, 100f, 100f).isEmpty())
        assertTrue(ConsistencyChartLayout.bars(days(10), 100, 0f, 100f).isEmpty())
        assertTrue(ConsistencyChartLayout.bars(days(10), 100, 100f, 0f).isEmpty())
    }

    @Test
    fun aZeroScaleDoesNotBreakTheLayout() {
        val bars = ConsistencyChartLayout.bars(days(10), scale = 0, width = 10f, height = 20f)
        assertTrue(bars[0].fillHeight.isFinite())
        assertEquals(20f, bars[0].fillHeight, 1e-4f)
    }

    @Test
    fun summaryCountsTrainedDaysTrenchDaysAndPoints() {
        val input = listOf(day(0, 80, trench = true), day(1), day(2, 40), day(3, 60, sessions = 2))
        assertEquals(ChartSummary(trainedDays = 3, trenchDays = 1, points = 180), ConsistencyChartLayout.summarize(input))
        assertEquals(ChartSummary(0, 0, 0), ConsistencyChartLayout.summarize(emptyList()))
    }

    @Test
    fun worksWithTheRealThirtyDaySeries() {
        val today = LocalDate.of(2026, 5, 31)
        val record = WorkoutRecord(
            date = today.minusDays(3).toString(), type = "Carrera", distanceKm = null, durationMin = null,
            paceSecPerKm = null, avgHr = null, maxHr = null, cadence = null, strideM = null, contactMs = null,
            verticalOscCm = null, powerW = null, tempC = null, aerobicTe = null, bodyBatteryImpact = null,
            puntosEsfuerzo = 120, esDiaTrinchera = true
        )
        val series = FormScore.dailyEffort(listOf(record), today)
        val bars = ConsistencyChartLayout.bars(series, FormScore.chartScale(series), 300f, 100f, minFill = 4f)
        assertEquals(30, bars.size)
        assertEquals(1, bars.count { it.trained })
        val trained = bars.single { it.trained }
        assertTrue(trained.isTrenchDay)
        assertEquals(100f, trained.fillHeight, 1e-3f)
        assertTrue(bars.last().isToday)
    }
}
