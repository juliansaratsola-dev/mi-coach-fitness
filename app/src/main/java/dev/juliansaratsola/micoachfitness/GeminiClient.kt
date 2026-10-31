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

class CoachException(message: String) : Exception(message)

/** Minimal Gemini REST client: sends screenshots + history prompt and returns the structured JSON review. */
class GeminiClient(private val context: Context, private val apiKey: String) {

    fun review(images: List<Uri>, history: List<WorkoutRecord>, todayIso: String, notes: String?): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", CoachPrompt.userPrompt(history, todayIso, notes)))
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
        val response = request("$BASE/${resolveModel()}:generateContent", "POST", body.toString())
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

    /** Asks the API which models this key can use, so the app does not depend on a retired model name. */
    private fun resolveModel(): String {
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
        val picked = ModelPicker.pickFlash(names) ?: throw CoachException("No hay un modelo Gemini Flash disponible para esta clave.")
        return "models/$picked"
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
                throw CoachException("Gemini respondió $code. $message".trim())
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
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_SIDE = 2000
    }
}
