package dev.juliansaratsola.micoachfitness

/**
 * Decides whether Garmin Connect was the foreground app inside a window
 * around the screenshot timestamp. Pure logic so it can be unit-tested
 * without UsageStatsManager.
 */
object GarminActivityWindow {
    const val GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile"
    const val WINDOW_MS = 10_000L

    data class ForegroundTransition(
        val packageName: String,
        val timestampMs: Long,
        val toForeground: Boolean
    )

    fun isWithinWindow(
        captureTimeMs: Long,
        eventTimeMs: Long,
        windowMs: Long = WINDOW_MS
    ): Boolean {
        if (windowMs < 0L) return false
        return eventTimeMs in (captureTimeMs - windowMs)..(captureTimeMs + windowMs)
    }

    fun wasActiveAround(
        transitions: List<ForegroundTransition>,
        captureTimeMs: Long,
        packageName: String = GARMIN_PACKAGE,
        windowMs: Long = WINDOW_MS
    ): Boolean {
        if (windowMs < 0L || packageName.isBlank()) return false
        val windowStart = captureTimeMs - windowMs
        val windowEnd = captureTimeMs + windowMs
        var current: String? = null
        var since = Long.MIN_VALUE
        for (transition in transitions.sortedBy { it.timestampMs }) {
            if (transition.packageName.isBlank()) continue
            if (transition.toForeground) {
                if (current == packageName && overlaps(since, transition.timestampMs, windowStart, windowEnd)) {
                    return true
                }
                current = transition.packageName
                since = transition.timestampMs
            } else if (transition.packageName == current) {
                if (current == packageName && overlaps(since, transition.timestampMs, windowStart, windowEnd)) {
                    return true
                }
                current = null
                since = Long.MIN_VALUE
            }
        }
        return current == packageName && overlaps(since, Long.MAX_VALUE, windowStart, windowEnd)
    }

    private fun overlaps(start: Long, end: Long, windowStart: Long, windowEnd: Long): Boolean {
        return start <= windowEnd && end >= windowStart
    }
}
