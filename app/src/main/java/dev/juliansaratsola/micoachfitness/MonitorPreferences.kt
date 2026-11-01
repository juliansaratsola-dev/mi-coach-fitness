package dev.juliansaratsola.micoachfitness

import android.content.Context

class MonitorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var setupShown: Boolean
        get() = prefs.getBoolean(KEY_SETUP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SETUP, value).apply()
        }

    val resetVersion: Long
        get() = prefs.getLong(KEY_RESET_VERSION, 0L)

    var geminiApiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API, value.trim()).apply()
        }

    var lastHealthSessionEnd: Long
        get() = prefs.getLong(KEY_HC_END, 0L)
        set(value) {
            prefs.edit().putLong(KEY_HC_END, value).apply()
        }

    var healthOptionalAsked: Boolean
        get() = prefs.getBoolean(KEY_HC_OPT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HC_OPT, value).apply()
        }

    var healthWatchEnabled: Boolean
        get() = prefs.getBoolean(KEY_HC_WATCH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HC_WATCH, value).apply()
        }

    /** Optional age, only used to estimate the maximum heart rate for the effort points. Null when not set. */
    var userAge: Int?
        get() = prefs.getInt(KEY_AGE, 0).takeIf { it in EffortCalculator.MIN_AGE..EffortCalculator.MAX_AGE }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_AGE) else editor.putInt(KEY_AGE, value)
            editor.apply()
        }

    /** Optional body weight in kilograms, used only for local W/kg calculations. */
    var userWeightKg: Double?
        get() = prefs.getFloat(KEY_WEIGHT_KG, 0f).toDouble().takeIf {
            it.isFinite() && it in WorkoutRecord.MIN_BODY_WEIGHT_KG..WorkoutRecord.MAX_BODY_WEIGHT_KG
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null || !value.isFinite() || value !in WorkoutRecord.MIN_BODY_WEIGHT_KG..WorkoutRecord.MAX_BODY_WEIGHT_KG) {
                editor.remove(KEY_WEIGHT_KG)
            } else {
                editor.putFloat(KEY_WEIGHT_KG, value.toFloat())
            }
            editor.apply()
        }

    fun pendingCaptures(): List<String> =
        prefs.getString(KEY_PENDING, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun addPendingCapture(uri: String) {
        val updated = (pendingCaptures() + uri).distinct().takeLast(MAX_PENDING)
        prefs.edit().putString(KEY_PENDING, updated.joinToString("\n")).apply()
    }

    fun clearPendingCaptures() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    fun removePendingCapture(uri: String) {
        val updated = pendingCaptures().filterNot { it == uri }
        val editor = prefs.edit()
        if (updated.isEmpty()) editor.remove(KEY_PENDING) else editor.putString(KEY_PENDING, updated.joinToString("\n"))
        editor.apply()
    }

    fun resetLocalSettings(): Boolean = synchronized(RESET_LOCK) {
        val setupWasShown = setupShown
        val nextVersion = resetVersion + 1
        prefs.edit()
            .clear()
            .putBoolean(KEY_SETUP, setupWasShown)
            .putLong(KEY_RESET_VERSION, nextVersion)
            .commit()
    }

    fun isHandled(id: Long): Boolean = handledIds().contains(id)

    fun markHandled(id: Long) {
        val updated = (handledIds() + id).distinct().takeLast(MAX_HANDLED)
        prefs.edit().putString(KEY_HANDLED, updated.joinToString(",")).apply()
    }

    private fun handledIds(): List<Long> {
        return prefs.getString(KEY_HANDLED, null)
            ?.split(',')
            ?.mapNotNull { token -> token.toLongOrNull() }
            ?: emptyList()
    }

    companion object {
        internal val RESET_LOCK = Any()
        private const val PREFS = "monitor_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HANDLED = "handled_ids"
        private const val KEY_SETUP = "setup_shown"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_HC_END = "hc_last_session_end"
        private const val KEY_HC_OPT = "hc_optional_asked"
        private const val KEY_HC_WATCH = "hc_watch"
        private const val KEY_AGE = "user_age"
        private const val KEY_WEIGHT_KG = "user_weight_kg"
        private const val KEY_PENDING = "pending_captures"
        private const val KEY_RESET_VERSION = "local_reset_version"
        private const val MAX_HANDLED = 50
        private const val MAX_PENDING = 6
    }
}
