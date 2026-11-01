package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FormScoreTest {
    private val today = LocalDate.of(2026, 5, 31)

    private fun session(daysAgo: Int, points: Int, trench: Boolean = false, date: String? = null) = WorkoutRecord(
        date = date ?: today.minusDays(daysAgo.toLong()).toString(), type = "Carrera", distanceKm = null,
        durationMin = null, paceSecPerKm = null, avgHr = null, maxHr = null, cadence = null, strideM = null,
        contactMs = null, verticalOscCm = null, powerW = null, tempC = null, aerobicTe = null,
        bodyBatteryImpact = null, puntosEsfuerzo = points, esDiaTrinchera = trench
    )

    @Test
    fun noWorkoutsMeansZero() {
        val snapshot = FormScore.snapshot(emptyList(), today)
        assertEquals(0.0, snapshot.score, 0.0)
        assertNull(snapshot.daysSinceLast)
        assertEquals(0, snapshot.windowPoints)
        assertEquals(1, snapshot.level.number)
        assertEquals("Oxidado", snapshot.level.title)
    }

    @Test
    fun trainingTodayHasNoDecay() {
        val snapshot = FormScore.snapshot(listOf(session(0, 100), session(3, 60)), today)
        assertEquals(160.0, snapshot.score, 1e-9)
        assertEquals(0, snapshot.daysSinceLast)
        assertEquals(2, snapshot.trainedDays)
    }

    @Test
    fun gravityDividesByDaysSinceLastWorkoutPlusOne() {
        val score = FormScore.calculate(listOf(session(2, 100), session(5, 60)), today)
        assertEquals(160.0 / 3, score, 1e-9)
    }

    @Test
    fun oneRestDayHalvesTheScore() {
        val trainedToday = FormScore.calculate(listOf(session(0, 100)), today)
        val restedYesterday = FormScore.calculate(listOf(session(1, 100)), today)
        assertEquals(trainedToday / 2, restedYesterday, 1e-9)
    }

    @Test
    fun windowKeepsTheLast30DaysIncludingToday() {
        val snapshot = FormScore.snapshot(listOf(session(29, 40), session(30, 1000), session(0, 10)), today)
        assertEquals(50, snapshot.windowPoints)
        assertEquals(50.0, snapshot.score, 1e-9)
    }

    @Test
    fun aWorkoutOutsideTheWindowStillSetsTheGravity() {
        val snapshot = FormScore.snapshot(listOf(session(40, 100)), today)
        assertEquals(40, snapshot.daysSinceLast)
        assertEquals(0, snapshot.windowPoints)
        assertEquals(0.0, snapshot.score, 0.0)
    }

    @Test
    fun futureAndInvalidDatesAreIgnored() {
        val sessions = listOf(session(-3, 500), session(0, 999, date = "sin-fecha"), session(1, 80))
        val snapshot = FormScore.snapshot(sessions, today)
        assertEquals(1, snapshot.daysSinceLast)
        assertEquals(80, snapshot.windowPoints)
        assertEquals(40.0, snapshot.score, 1e-9)
    }

    @Test
    fun workoutsOnTheSameDayAreSummed() {
        val snapshot = FormScore.snapshot(listOf(session(0, 30), session(0, 45)), today)
        assertEquals(75, snapshot.windowPoints)
        assertEquals(1, snapshot.trainedDays)
        assertEquals(2, snapshot.sessionsInWindow)
    }

    @Test
    fun levelsGrowWithTheSquareRootOfTheScore() {
        assertEquals(1, FormScore.levelFor(0.0).number)
        assertEquals(1, FormScore.levelFor(24.9).number)
        assertEquals(2, FormScore.levelFor(25.0).number)
        assertEquals(3, FormScore.levelFor(100.0).number)
        assertEquals(4, FormScore.levelFor(225.0).number)
    }

    @Test
    fun levelProgressIsTheFractionTowardsTheNextLevel() {
        assertEquals(0f, FormScore.levelFor(25.0).progress, 1e-6f)
        assertEquals(0.5f, FormScore.levelFor(62.5).progress, 1e-6f)
        assertEquals(0f, FormScore.levelFor(0.0).progress, 1e-6f)
        val level = FormScore.levelFor(62.5)
        assertEquals(25.0, level.floorScore, 1e-9)
        assertEquals(100.0, level.nextScore, 1e-9)
    }

    @Test
    fun invalidScoresFallBackToLevelOne() {
        assertEquals(1, FormScore.levelFor(-10.0).number)
        assertEquals(1, FormScore.levelFor(Double.NaN).number)
        assertEquals(1, FormScore.levelFor(Double.NEGATIVE_INFINITY).number)
    }

    @Test
    fun snapshotTellsHowManyPointsAreMissingForTheNextLevel() {
        val fresh = FormScore.snapshot(emptyList(), today)
        assertEquals(25, fresh.pointsToNextLevel)
        assertNull(fresh.gravityDivisor)
        // 125 points one day ago: score 62.5 sits in level 2 (25..100), so 37.5 points are left, rounded up.
        val mid = FormScore.snapshot(listOf(session(1, 125)), today)
        assertEquals(62.5, mid.score, 1e-9)
        assertEquals(2, mid.level.number)
        assertEquals(38, mid.pointsToNextLevel)
        // Never reports 0 missing points, even a hair below the threshold.
        val almost = FormSnapshot(99.9999, 100, 1, 1, 0, FormScore.levelFor(99.9999))
        assertEquals(2, almost.level.number)
        assertEquals(1, almost.pointsToNextLevel)
    }

    @Test
    fun gravityDivisorIsDaysSinceLastPlusOne() {
        assertEquals(1, FormScore.snapshot(listOf(session(0, 50)), today).gravityDivisor)
        assertEquals(4, FormScore.snapshot(listOf(session(3, 50)), today).gravityDivisor)
    }

    @Test
    fun displayedScoreIsRoundedDownSoItNeverClaimsAnUnreachedLevel() {
        // 74 points two days ago: 74 / 3 = 24.67 is still level 1, so it must not read as 25.
        val short = FormScore.snapshot(listOf(session(2, 74)), today)
        assertEquals(1, short.level.number)
        assertEquals(24, short.scoreWhole)
        assertEquals(1, short.pointsToNextLevel)
        val reached = FormScore.snapshot(listOf(session(2, 75)), today)
        assertEquals(2, reached.level.number)
        assertEquals(25, reached.scoreWhole)
    }

    @Test
    fun titlesFollowTheLevel() {
        assertEquals("Oxidado", FormScore.titleFor(1))
        assertEquals("Recluta", FormScore.titleFor(2))
        assertEquals("Recluta", FormScore.titleFor(3))
        assertEquals("Soldado", FormScore.titleFor(4))
        assertEquals("Veterano", FormScore.titleFor(6))
        assertEquals("Curtido", FormScore.titleFor(9))
        assertEquals("Forjado", FormScore.titleFor(12))
        assertEquals("Inquebrantable", FormScore.titleFor(16))
    }

    @Test
    fun dailyEffortCoversThirtyDaysOldestFirst() {
        val days = FormScore.dailyEffort(
            listOf(session(0, 70, trench = true), session(29, 40), session(30, 500), session(7, 20, date = "mal")),
            today
        )
        assertEquals(FormScore.WINDOW_DAYS, days.size)
        assertEquals(today.minusDays(29), days.first().date)
        assertEquals(today, days.last().date)
        assertEquals(40, days.first().points)
        assertEquals(70, days.last().points)
        assertTrue(days.last().isTrenchDay)
        assertFalse(days.first().isTrenchDay)
        assertEquals(2, days.count { it.trained })
        assertTrue(days.filter { !it.trained }.all { it.points == 0 })
    }

    @Test
    fun chartScaleNeverDropsBelowItsFloor() {
        val small = FormScore.dailyEffort(listOf(session(0, 20)), today)
        val big = FormScore.dailyEffort(listOf(session(0, 120), session(1, 80)), today)
        assertEquals(FormScore.MIN_CHART_SCALE, FormScore.chartScale(small))
        assertEquals(120, FormScore.chartScale(big))
        assertEquals(FormScore.MIN_CHART_SCALE, FormScore.chartScale(emptyList()))
    }
}
