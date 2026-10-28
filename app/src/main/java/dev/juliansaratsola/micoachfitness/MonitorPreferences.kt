package dev.juliansaratsola.micoachfitness

import android.content.Context

class MonitorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
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
        private const val MAX_HANDLED = 50
    }
}
