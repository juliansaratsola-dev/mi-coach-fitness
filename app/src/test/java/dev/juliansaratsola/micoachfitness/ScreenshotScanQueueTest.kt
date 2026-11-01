package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotScanQueueTest {
    @Test
    fun keepsEveryImageIdInAChangeBurst() {
        val queue = ScreenshotScanQueue()
        queue.enqueue(41L)
        queue.enqueue(42L)
        queue.enqueue(41L)

        assertEquals(ScreenshotScanQueue.Batch(false, listOf(41L, 42L)), queue.drain())
        assertTrue(queue.drain().isEmpty)
    }

    @Test
    fun uriWithoutImageIdRequestsRecentScanWithoutDroppingKnownIds() {
        val queue = ScreenshotScanQueue()
        queue.enqueue(41L)
        queue.enqueue(null)
        queue.enqueue(42L)

        val batch = queue.drain()
        assertTrue(batch.requiresRecentScan)
        assertEquals(listOf(41L, 42L), batch.imageIds)
        assertFalse(queue.drain().requiresRecentScan)
    }

    @Test
    fun screenshotAlertsDoNotReuseForegroundOrHealthNotificationIds() {
        val first = ScreenshotNotificationIds.forImage(1001)
        val second = ScreenshotNotificationIds.forImage(1002)

        assertTrue(first != second)
        assertTrue(first != 1001 && first != 2002)
        assertTrue(second != 1001 && second != 2002)
    }
}
