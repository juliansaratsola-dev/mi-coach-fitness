package dev.juliansaratsola.micoachfitness

/** Coalesces MediaStore changes without dropping the image IDs delivered in a burst. */
class ScreenshotScanQueue {
    private val imageIds = linkedSetOf<Long>()
    private var requiresRecentScan = false

    @Synchronized
    fun enqueue(imageId: Long?) {
        if (imageId == null) {
            requiresRecentScan = true
        } else {
            imageIds += imageId
        }
    }

    @Synchronized
    fun drain(): Batch {
        val batch = Batch(requiresRecentScan, imageIds.toList())
        requiresRecentScan = false
        imageIds.clear()
        return batch
    }

    data class Batch(val requiresRecentScan: Boolean, val imageIds: List<Long>) {
        val isEmpty: Boolean
            get() = !requiresRecentScan && imageIds.isEmpty()
    }
}

object ScreenshotNotificationIds {
    private const val SCREENSHOT_ALERT_BASE = 0x40000000
    private const val IMAGE_ID_MASK = 0x3fffffffL

    fun forImage(imageId: Long): Int =
        SCREENSHOT_ALERT_BASE + (imageId and IMAGE_ID_MASK).toInt()
}
