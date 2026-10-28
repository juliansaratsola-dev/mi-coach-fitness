package dev.juliansaratsola.micoachfitness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MonitorPreferences(context).isEnabled) return
        if (!PermissionHelper.hasPhotoAccess(context) || !PermissionHelper.hasNotificationAccess(context)) {
            Log.w(TAG, "Boot restore skipped: runtime permissions missing")
            return
        }
        try {
            context.startForegroundService(
                Intent(context, ScreenshotMonitorService::class.java)
            )
        } catch (error: Exception) {
            Log.w(TAG, "Unable to restore monitor after boot", error)
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
