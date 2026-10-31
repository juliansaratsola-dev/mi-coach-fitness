package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class CoachException(message: String, val retryable: Boolean = false, val modelUnavailable: Boolean = false) : Exception(message)

/** Gemini REST client for structured workout reviews and contextual chat. */
class GeminiClient(private val context: Context, private val apiKey: String) {

    fun chat(history: List<WorkoutRecord>, conversation: List<CoachChatMessage>): String {
        val contents = JSONArray()
        conversation.takeLast(MAX_CHAT_MESSAGES).forEach { message ->
            contents.put(
                JSONObject()
                    .put("role", message.role.apiRole)
                    .put("parts", JSONArray().put(JSONObject().put("text", message.text)))
            )
        }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", CoachPrompt.chatSystemInstruction(history)))
                )
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.7))
        val response = generateWithFallback(body.toString())
        val parts = JSONObject(response)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw CoachException("Gemini no devolvió contenido.")
        val answer = (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .joinToString("\n")
            .trim()
        if (answer.isBlank()) throw CoachException("Gemini no devolvió contenido.")
        return answer
    }

    fun review(
        images: List<Uri>,
        history: List<WorkoutRecord>,
        todayIso: String,
        notes: String?,
        workoutData: String? = null
    ): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", CoachPrompt.userPrompt(history, todayIso, notes, workoutData, images.isNotEmpty())))
        for (uri in images) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", encodeImage(uri))
                )
            )
        }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", CoachPrompt.SYSTEM_INSTRUCTION)))
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject(CoachPrompt.RESPONSE_SCHEMA))
                    .put("temperature", 0.6)
            )
        val response = generateWithFallback(body.toString())
        val text = JSONObject(response)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text")
        if (text.isNullOrBlank()) throw CoachException("Gemini no devolvió contenido.")
        return try {
            JSONObject(text)
        } catch (error: Exception) {
            throw CoachException("Respuesta de Gemini no válida.")
        }
    }

    /** Retries transient overload errors, then falls back to the next available model. */
    private fun generateWithFallback(payload: String): String {
        var last: CoachException? = null
        for (model in resolveModels().take(MAX_MODELS)) {
            for (attempt in 0 until ATTEMPTS_PER_MODEL) {
                try {
                    return request("$BASE/$model:generateContent", "POST", payload)
                } catch (error: CoachException) {
                    if (!error.retryable && !error.modelUnavailable) throw error
                    last = error
                    if (error.modelUnavailable) break
                    if (attempt < ATTEMPTS_PER_MODEL - 1) Thread.sleep(3_000L * (attempt + 1))
                }
            }
        }
        throw CoachException("Gemini está saturado en este momento. Intenta de nuevo en unos minutos. (${last?.message.orEmpty()})")
    }

    /** Asks the API which models this key can use, so the app does not depend on a retired model name. */
    private fun resolveModels(): List<String> {
        val list = JSONObject(request("$BASE/models?pageSize=200", "GET", null)).optJSONArray("models")
        val names = mutableListOf<String>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val model = list.optJSONObject(i) ?: continue
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) {
                    names += model.optString("name")
                }
            }
        }
        val ranked = ModelPicker.fallbackOrder(names)
        if (ranked.isEmpty()) throw CoachException("No hay un modelo Gemini Flash disponible para esta clave.")
        return ranked.map { "models/$it" }
    }

    private fun request(url: String, method: String, payload: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("x-goog-api-key", apiKey)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching {
                    JSONObject(detail).getJSONObject("error").getString("message")
                }.getOrDefault("")
                throw CoachException("Gemini respondió $code. $message".trim(), retryable = code == 429 || code >= 500, modelUnavailable = code == 404)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeImage(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw CoachException("No se pudo leer una captura.")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val ATTEMPTS_PER_MODEL = 2
        private const val MAX_MODELS = 3
        private const val MAX_CHAT_MESSAGES = 24
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_SIDE = 2000
    }
}
