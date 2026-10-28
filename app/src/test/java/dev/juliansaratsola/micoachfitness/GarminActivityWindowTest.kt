package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminActivityWindowTest {
    private val capture = 1_700_000_000_000L
    private val garmin = GarminActivityWindow.GARMIN_PACKAGE

    @Test
    fun garminStillForegroundInsideWindow() {
        val events = listOf(
            fg(garmin, capture - 5_000L)
        )
        assertTrue(GarminActivityWindow.wasActiveAround(events, capture))
    }

    @Test
    fun garminClosedBeforeWindow() {
        val events = listOf(
            fg(garmin, capture - 30_000L),
            bg(garmin, capture - 12_000L)
        )
        assertFalse(GarminActivityWindow.wasActiveAround(events, capture))
    }

    @Test
    fun garminIntervalOverlapsWindow() {
        val events = listOf(
            fg(garmin, capture - 15_000L),
            bg(garmin, capture + 2_000L)
        )
        assertTrue(GarminActivityWindow.wasActiveAround(events, capture))
    }

    @Test
    fun garminResumedJustAfterCapture() {
        val events = listOf(fg(garmin, capture + 3_000L))
        assertTrue(GarminActivityWindow.wasActiveAround(events, capture))
    }

    @Test
    fun otherAppDoesNotMatch() {
        val events = listOf(fg("com.example.other", capture - 1_000L))
        assertFalse(GarminActivityWindow.wasActiveAround(events, capture))
    }

    @Test
    fun inclusiveWindowEdges() {
        assertTrue(GarminActivityWindow.isWithinWindow(capture, capture - 10_000L))
        assertTrue(GarminActivityWindow.isWithinWindow(capture, capture + 10_000L))
        assertFalse(GarminActivityWindow.isWithinWindow(capture, capture - 10_001L))
        assertFalse(GarminActivityWindow.isWithinWindow(capture, capture + 10_001L))
    }

    @Test
    fun resumeExactlyAtWindowEndCounts() {
        val events = listOf(fg(garmin, capture + 10_000L))
        assertTrue(GarminActivityWindow.wasActiveAround(events, capture))
    }

    private fun fg(packageName: String, timestampMs: Long) =
        GarminActivityWindow.ForegroundTransition(packageName, timestampMs, toForeground = true)

    private fun bg(packageName: String, timestampMs: Long) =
        GarminActivityWindow.ForegroundTransition(packageName, timestampMs, toForeground = false)
}
