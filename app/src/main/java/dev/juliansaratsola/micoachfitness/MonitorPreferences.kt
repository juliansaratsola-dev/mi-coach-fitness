package dev.juliansaratsola.micoachfitness

import android.content.Context

class MonitorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var geminiApiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API, value.trim()).apply()
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
        private const val PREFS = "monitor_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HANDLED = "handled_ids"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_PENDING = "pending_captures"
        private const val MAX_HANDLED = 50
        private const val MAX_PENDING = 6
    }
}
