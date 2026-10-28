package dev.juliansaratsola.micoachfitness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenshotMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val reader by lazy { MediaStoreScreenshotReader(this) }
    private val garminChecker by lazy { GarminUsageChecker(this) }
    private val scanScheduled = AtomicBoolean(false)
    private val inFlight = mutableSetOf<Long>()
    private val scanLock = Any()
    private var observer: ContentObserver? = null
    private var pendingUri: Uri? = null

    @Volatile
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startAsForeground()
        if (intent?.action == ACTION_STOP || !MonitorPreferences(this).isEnabled) {
            shutdown(disable = intent?.action == ACTION_STOP)
            return START_NOT_STICKY
        }
        if (!PermissionHelper.hasPhotoAccess(this) || !PermissionHelper.hasNotificationAccess(this)) {
            Log.w(TAG, "Missing runtime permissions; stopping monitor")
            shutdown(disable = true)
            return START_NOT_STICKY
        }
        running = true
        registerObserver()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        handler.removeCallbacksAndMessages(null)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        worker.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun shutdown(disable: Boolean) {
        if (disable) MonitorPreferences(this).isEnabled = false
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerObserver() {
        if (observer != null) return
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleScan(null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
                scheduleScan(uri)
            }
        }
        observer = contentObserver
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        Log.i(TAG, "Watching MediaStore.Images")
    }

    private fun scheduleScan(uri: Uri?) {
        if (destroyed) return
        synchronized(scanLock) {
            if (uri != null) pendingUri = uri
            if (!scanScheduled.compareAndSet(false, true)) return
        }
        handler.postDelayed({
            val target = synchronized(scanLock) {
                scanScheduled.set(false)
                val value = pendingUri
                pendingUri = null
                value
            }
            worker.execute { scan(target) }
        }, SCAN_DELAY_MS)
    }

    private fun scan(uri: Uri?) {
        if (destroyed || !MonitorPreferences(this).isEnabled) return
        val images = try {
            reader.loadRecentCandidates(uri)
        } catch (error: Exception) {
            Log.w(TAG, "Scan failed", error)
            emptyList()
        }
        val now = System.currentTimeMillis()
        for (image in images) {
            consider(image, now, attempt = 0)
        }
    }

    private fun consider(image: CapturedImage, nowMs: Long, attempt: Int) {
        if (destroyed) return
        val prefs = MonitorPreferences(this)
        if (prefs.isHandled(image.id)) return
        synchronized(inFlight) {
            if (attempt == 0 && !inFlight.add(image.id)) return
        }
        try {
            if (image.takenAtMs > 0L && nowMs - image.takenAtMs > MAX_AGE_MS) {
                prefs.markHandled(image.id)
                return
            }
            if (!ScreenshotClassifier.isScreenshot(image.displayName, image.relativePath, image.dataPath)) {
                return
            }
            when (garminChecker.wasGarminActiveAround(image.takenAtMs)) {
                GarminUsageChecker.CheckResult.INACTIVE -> {
                    if (attempt < 1) {
                        handler.postDelayed({
                            worker.execute { consider(image, System.currentTimeMillis(), attempt + 1) }
                        }, RETRY_DELAY_MS)
                        return
                    }
                    prefs.markHandled(image.id)
                    Log.i(TAG, "Screenshot ${image.id} ignored: Garmin not in the 10s window")
                }
                GarminUsageChecker.CheckResult.UNAVAILABLE -> {
                    Log.w(TAG, "Usage access unavailable; notifying without Garmin confirmation")
                    prefs.markHandled(image.id)
                    handler.post { notifyAnalyze(image) }
                }
                GarminUsageChecker.CheckResult.ACTIVE -> {
                    prefs.markHandled(image.id)
                    handler.post { notifyAnalyze(image) }
                }
            }
        } finally {
            val waitingForRetry = attempt < 1 && !prefs.isHandled(image.id) &&
                ScreenshotClassifier.isScreenshot(image.displayName, image.relativePath, image.dataPath)
            if (!waitingForRetry) {
                synchronized(inFlight) { inFlight.remove(image.id) }
            }
        }
    }

    private fun notifyAnalyze(image: CapturedImage) {
        if (destroyed || !PermissionHelper.hasNotificationAccess(this)) return
        if (image.uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val analyze = Intent(this, AnalyzeWithCopilotActivity::class.java).apply {
            data = image.uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val requestCode = (image.id % Int.MAX_VALUE).toInt()
        val pending = PendingIntent.getActivity(
            this,
            requestCode,
            analyze,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.alert_title))
            .setContentText(getString(R.string.alert_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.alert_text)))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_monitor,
                    getString(R.string.action_analyze_copilot),
                    pending
                ).build()
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        val alertId = if (requestCode == FG_NOTIFICATION_ID) requestCode + 1 else requestCode
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(alertId, notification)
        } catch (security: SecurityException) {
            Log.w(TAG, "Unable to post analyze notification", security)
        }
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenshotMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.fg_title))
            .setContentText(getString(R.string.fg_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_monitor,
                    getString(R.string.action_stop),
                    stop
                ).build()
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FG_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FG_NOTIFICATION_ID, notification)
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            getString(R.string.fg_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fg_channel_desc)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alert_channel_desc)
        }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(alerts)
    }

    companion object {
        private const val TAG = "ScreenshotMonitor"
        private const val CHANNEL_MONITOR = "screenshot_monitor"
        private const val CHANNEL_ALERTS = "screenshot_alerts"
        private const val FG_NOTIFICATION_ID = 1001
        private const val SCAN_DELAY_MS = 500L
        private const val RETRY_DELAY_MS = 1_500L
        private const val MAX_AGE_MS = 3L * 60L * 1000L
        const val ACTION_STOP = "dev.juliansaratsola.micoachfitness.STOP_MONITOR"

        @Volatile
        var running: Boolean = false
            private set

        val isRunning: Boolean
            get() = running
    }
}
