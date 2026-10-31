package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var monitorSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var permissionSummary: TextView
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
        setContentView(R.layout.activity_main)
        prefs = MonitorPreferences(this)
        monitorSwitch = findViewById(R.id.monitorSwitch)
        statusText = findViewById(R.id.statusText)
        permissionSummary = findViewById(R.id.permissionSummary)
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener {
            requestMissingPermissions(enableAfter = false)
        }
        findViewById<Button>(R.id.usageSettingsButton).setOnClickListener { openUsageSettings() }
        val keyInput = findViewById<EditText>(R.id.apiKeyInput)
        keyInput.setText(prefs.geminiApiKey)
        findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            prefs.geminiApiKey = keyInput.text.toString()
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.openCoachButton).setOnClickListener {
            startActivity(Intent(this, CoachActivity::class.java))
        }
        refreshUi()
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

    private fun requestMissingPermissions(enableAfter: Boolean) {
        val missing = PermissionHelper.missingRuntimePermissions(this)
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.permissions_ok, Toast.LENGTH_SHORT).show()
            refreshUi()
            return
        }
        pendingEnable = enableAfter
        requestPermissions(missing, REQUEST_PERMISSIONS)
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

    private fun openUsageSettings() {
        val withPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(withPackage)
        } catch (error: Exception) {
            Log.w(TAG, "Usage settings with package URI failed", error)
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (fallback: Exception) {
                Log.w(TAG, "Usage settings unavailable", fallback)
                Toast.makeText(this, R.string.usage_settings_unavailable, Toast.LENGTH_LONG).show()
            }
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
        permissionSummary.text = getString(
            R.string.permission_summary,
            stateLabel(PermissionHelper.hasNotificationAccess(this)),
            stateLabel(PermissionHelper.hasPhotoAccess(this)),
            stateLabel(GarminUsageChecker.hasUsageAccess(this))
        )
    }

    private fun stateLabel(granted: Boolean): String {
        return getString(if (granted) R.string.state_granted else R.string.state_missing)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 100
    }
}
