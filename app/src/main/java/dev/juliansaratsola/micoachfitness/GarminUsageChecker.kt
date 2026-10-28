package dev.juliansaratsola.micoachfitness

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * Confirms Garmin Connect was recently active around a capture.
 * Falls back to UsageStats lastTimeUsed / lastTimeVisible when the event
 * stream is missing, and reports UNAVAILABLE when the app-op is not granted.
 */
class GarminUsageChecker(private val context: Context) {

    enum class CheckResult {
        ACTIVE,
        INACTIVE,
        UNAVAILABLE
    }

    fun wasGarminActiveAround(captureTimeMs: Long): CheckResult {
        if (!hasUsageAccess(context)) return CheckResult.UNAVAILABLE
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
            ?: return CheckResult.UNAVAILABLE
        return try {
            val transitions = readTransitions(usageStatsManager, captureTimeMs)
            val activeByEvents = transitions.isNotEmpty() &&
                GarminActivityWindow.wasActiveAround(transitions, captureTimeMs)
            if (activeByEvents || statsIndicateRecentUse(usageStatsManager, captureTimeMs)) {
                CheckResult.ACTIVE
            } else {
                CheckResult.INACTIVE
            }
        } catch (security: SecurityException) {
            Log.w(TAG, "Usage stats denied", security)
            CheckResult.UNAVAILABLE
        }
    }

    private fun readTransitions(
        usageStatsManager: UsageStatsManager,
        captureTimeMs: Long
    ): List<GarminActivityWindow.ForegroundTransition> {
        val start = captureTimeMs - LOOKBACK_MS
        val end = captureTimeMs + GarminActivityWindow.WINDOW_MS
        val events = usageStatsManager.queryEvents(start, end) ?: return emptyList()
        val event = UsageEvents.Event()
        val transitions = mutableListOf<GarminActivityWindow.ForegroundTransition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val toForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            val toBackground = event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            if (!toForeground && !toBackground) continue
            val packageName = event.packageName ?: continue
            transitions += GarminActivityWindow.ForegroundTransition(
                packageName = packageName,
                timestampMs = event.timeStamp,
                toForeground = toForeground
            )
        }
        return transitions
    }

    private fun statsIndicateRecentUse(
        usageStatsManager: UsageStatsManager,
        captureTimeMs: Long
    ): Boolean {
        val queryStart = captureTimeMs - STATS_QUERY_PADDING_MS
        val queryEnd = captureTimeMs + GarminActivityWindow.WINDOW_MS
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            queryStart,
            queryEnd
        ) ?: return false
        return stats.any { stat ->
            stat.packageName == GarminActivityWindow.GARMIN_PACKAGE &&
                (GarminActivityWindow.isWithinWindow(captureTimeMs, stat.lastTimeUsed) ||
                    GarminActivityWindow.isWithinWindow(captureTimeMs, stat.lastTimeVisible))
        }
    }

    companion object {
        private const val TAG = "GarminUsageChecker"
        private const val LOOKBACK_MS = 6L * 60L * 60L * 1000L
        private const val STATS_QUERY_PADDING_MS = 2L * 60L * 1000L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
