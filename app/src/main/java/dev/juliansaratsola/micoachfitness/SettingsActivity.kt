package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.app.AlertDialog
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
        val ageInput = content.findViewById<EditText>(R.id.ageInput)
        ageInput.setText(prefs.userAge?.toString().orEmpty())
        content.findViewById<TextView>(R.id.ageHelp).text =
            getString(R.string.age_help, EffortCalculator.DEFAULT_MAX_HR.toInt())
        content.findViewById<Button>(R.id.saveAgeButton).setOnClickListener { saveAge(ageInput) }
        content.findViewById<Button>(R.id.resetAppButton).setOnClickListener {
            confirmReset(keyInput, ageInput)
        }
    }

    /** A blank field clears the age; anything else must be a plausible age or nothing is saved. */
    private fun saveAge(input: EditText) {
        val typed = input.text.toString().trim()
        if (typed.isEmpty()) {
            prefs.userAge = null
            val message = getString(R.string.age_cleared, EffortCalculator.DEFAULT_MAX_HR.toInt())
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        val age = typed.toIntOrNull()?.takeIf { it in EffortCalculator.MIN_AGE..EffortCalculator.MAX_AGE }
        if (age == null) {
            val message = getString(R.string.age_invalid, EffortCalculator.MIN_AGE, EffortCalculator.MAX_AGE)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        prefs.userAge = age
        Toast.makeText(this, R.string.age_saved, Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset(keyInput: EditText, ageInput: EditText) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_app_title)
            .setMessage(R.string.reset_app_message)
            .setNegativeButton(R.string.coach_close, null)
            .setPositiveButton(R.string.reset_app_action) { _, _ ->
                stopService(Intent(this, ScreenshotMonitorService::class.java))
                getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
                val resetSucceeded = synchronized(MonitorPreferences.RESET_LOCK) {
                    WorkoutStore(this).clear()
                    val chatCleared = CoachChatStore(this).clear()
                    val settingsCleared = prefs.resetLocalSettings()
                    chatCleared && settingsCleared
                }
                keyInput.text.clear()
                ageInput.text.clear()
                Toast.makeText(
                    this,
                    if (resetSucceeded) R.string.reset_app_done else R.string.reset_app_failed,
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
            .show()
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
