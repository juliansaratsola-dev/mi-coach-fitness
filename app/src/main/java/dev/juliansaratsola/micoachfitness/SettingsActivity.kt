package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Permissions, usage access and Gemini API key. Opened automatically on first launch. */
class SettingsActivity : Activity() {
    private lateinit var prefs: MonitorPreferences
    private lateinit var permissionSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MonitorPreferences(this)
        val content = layoutInflater.inflate(R.layout.activity_settings, null)
        BottomNav.attach(this, content, BottomNav.Tab.SETTINGS)
        permissionSummary = content.findViewById(R.id.permissionSummary)
        content.findViewById<TextView>(R.id.setupWelcome).visibility =
            if (intent.getBooleanExtra(EXTRA_FIRST_RUN, false)) android.view.View.VISIBLE else android.view.View.GONE
        content.findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener {
            val missing = PermissionHelper.missingRuntimePermissions(this)
            if (missing.isEmpty()) {
                Toast.makeText(this, R.string.permissions_ok, Toast.LENGTH_SHORT).show()
            } else {
                requestPermissions(missing, REQUEST_PERMISSIONS)
            }
        }
        content.findViewById<Button>(R.id.usageSettingsButton).setOnClickListener { openUsageSettings() }
        val keyInput = content.findViewById<EditText>(R.id.apiKeyInput)
        keyInput.setText(prefs.geminiApiKey)
        content.findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            prefs.geminiApiKey = keyInput.text.toString()
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        permissionSummary.text = getString(
            R.string.permission_summary,
            stateLabel(PermissionHelper.hasNotificationAccess(this)),
            stateLabel(PermissionHelper.hasPhotoAccess(this)),
            stateLabel(GarminUsageChecker.hasUsageAccess(this))
        )
    }

    private fun stateLabel(granted: Boolean): String =
        getString(if (granted) R.string.state_granted else R.string.state_missing)

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
                Toast.makeText(this, R.string.usage_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
        private const val TAG = "SettingsActivity"
        private const val REQUEST_PERMISSIONS = 101
    }
}
