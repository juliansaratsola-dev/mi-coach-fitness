package dev.juliansaratsola.micoachfitness

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class CoachChatRole(val apiRole: String) {
    USER("user"),
    COACH("model");

    companion object {
        fun fromApiRole(value: String): CoachChatRole? =
            values().firstOrNull { it.apiRole == value }
    }
}

data class CoachChatMessage(val role: CoachChatRole, val text: String)

/** Persists the local Coach conversation independently from workout history. */
class CoachChatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Throws(JSONException::class)
    fun all(): List<CoachChatMessage> {
        val saved = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        val array = JSONArray(saved)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val role = CoachChatRole.fromApiRole(item.optString("role")) ?: return@mapNotNull null
            val text = item.optString("text")
            if (text.isBlank()) null else CoachChatMessage(role, text)
        }
    }

    fun save(messages: List<CoachChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(JSONObject().put("role", message.role.apiRole).put("text", message.text))
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "coach_chat"
        private const val KEY_MESSAGES = "messages"
        private const val MAX_MESSAGES = 40
    }
}
