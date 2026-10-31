package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var monitorSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var prefs: MonitorPreferences
    private var pendingEnable = false

    private val switchListener = CompoundButton.OnCheckedChangeListener { button, isChecked ->
        if (!button.isPressed) return@OnCheckedChangeListener
        if (isChecked) {
            enableMonitoring()
        } else {
            disableMonitoring()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MonitorPreferences(this)
        BottomNav.attach(this, layoutInflater.inflate(R.layout.activity_main, null), BottomNav.Tab.HOME)
        monitorSwitch = findViewById(R.id.monitorSwitch)
        statusText = findViewById(R.id.statusText)
        refreshUi()
        if (savedInstanceState == null && !prefs.setupShown) {
            prefs.setupShown = true
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_FIRST_RUN, true))
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isEnabled && PermissionHelper.missingRuntimePermissions(this).isEmpty()) {
            startMonitorService()
        }
        refreshUi()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (pendingEnable) {
            if (PermissionHelper.missingRuntimePermissions(this).isEmpty()) {
                prefs.isEnabled = true
                startMonitorService()
            } else {
                prefs.isEnabled = false
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
            }
        }
        pendingEnable = false
        refreshUi()
    }

    private fun enableMonitoring() {
        val missing = PermissionHelper.missingRuntimePermissions(this)
        if (missing.isNotEmpty()) {
            pendingEnable = true
            requestPermissions(missing, REQUEST_PERMISSIONS)
            return
        }
        prefs.isEnabled = true
        startMonitorService()
        refreshUi()
    }

    private fun disableMonitoring() {
        prefs.isEnabled = false
        stopService(Intent(this, ScreenshotMonitorService::class.java))
        refreshUi()
    }

    private fun startMonitorService() {
        try {
            startForegroundService(Intent(this, ScreenshotMonitorService::class.java))
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start monitor", error)
            prefs.isEnabled = false
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUi() {
        monitorSwitch.setOnCheckedChangeListener(null)
        monitorSwitch.isChecked = prefs.isEnabled
        monitorSwitch.setOnCheckedChangeListener(switchListener)
        val service = if (ScreenshotMonitorService.isRunning) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
        val filter = if (GarminUsageChecker.hasUsageAccess(this)) {
            getString(R.string.garmin_filter_active)
        } else {
            getString(R.string.fallback_usage_missing)
        }
        statusText.text = getString(R.string.status_line, service, filter)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 100
    }
}
