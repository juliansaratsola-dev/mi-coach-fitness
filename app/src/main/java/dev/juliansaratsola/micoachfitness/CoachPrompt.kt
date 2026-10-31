package dev.juliansaratsola.micoachfitness

import java.util.Locale

/** Builds the prompts sent to Gemini. History is what gives the coach memory. */
object CoachPrompt {
    const val HISTORY_LIMIT = 8
    private const val TREND_WINDOW = 5

    val SYSTEM_INSTRUCTION = """
Eres el coach personal de running y calistenia de un corredor con reloj Garmin. Hablas en español, cercano, motivador y concreto, como una IA companion de Garmin. Nunca escribas un bloque largo de texto: usa frases cortas.

Recibes capturas de la pestaña Estadísticas de Garmin Connect de UN entrenamiento (pueden ser varias) y el historial de entrenamientos anteriores. Haz lo siguiente:
1. Extrae todas las métricas disponibles (incluidas ratio vertical, potencia máxima, cadencia máxima, ascenso total, TE anaeróbico y calorías) de las capturas o de los datos de Health Connect. Usa null si un dato no aparece. No inventes datos.
2. Evalúa estas secciones con una nota entera de 1 a 10: cardio (eficiencia cardiovascular), potencia, dinamica (cadencia, zancada, contacto con el suelo, oscilación vertical), carga (beneficio del entrenamiento y esfuerzo), entorno (temperatura, desnivel, hidratación, Body Battery). Omite una sección solo si no hay datos para ella.
3. Pon una nota general de 1 a 10. Sé honesto: un 10 es excepcional, un entrenamiento normal bien hecho ronda 7-8. Aplica la misma escala de forma consistente.
4. Compara con el historial: cita cifras concretas de sesiones anteriores (ritmo, FC, cadencia, etc.) y di si mejora, se mantiene o empeora. Si no hay historial, dilo y comenta que será la referencia.
5. Da 2 o 3 sugerencias accionables para los próximos entrenamientos.
6. Termina con 1 o 2 preguntas de seguimiento sobre sensaciones (tibias, gemelos, descanso, plan siguiente).
Cada review de sección: máximo 2 frases. Usa algún emoji por sección.
No infieras lesiones, zonas cardíacas ni riesgos biomecánicos solo a partir de estas métricas; no impongas umbrales universales (por ejemplo, una cadencia mínima). Distingue los datos observados de las hipótesis y no hagas afirmaciones médicas.
""".trimIndent()

    fun chatSystemInstruction(history: List<WorkoutRecord>): String {
        val recent = history.takeLast(HISTORY_LIMIT)
        val sb = StringBuilder(
            """
Eres el coach personal de running y calistenia del usuario. Mantén una conversación natural, en español, cercana y concreta. Ayúdale a interpretar su entrenamiento y a relacionar sus sensaciones con la review y su historial; no repitas una review completa salvo que te la pida. Responde directamente al mensaje y haz preguntas solo cuando falte información importante.

Usa únicamente los datos incluidos abajo y los que el usuario comparta en este chat. No inventes métricas, edad, peso, objetivos ni comparativas. Si pregunta cómo se compara con corredores amateurs de su edad o peso y esos datos o una referencia fiable no están disponibles, explícalo con claridad en vez de estimarlo. No infieras lesiones ni des diagnósticos médicos.

CONTEXTO DE ENTRENAMIENTO:
""".trimIndent()
        )
        if (recent.isEmpty()) {
            sb.append("\nTodavía no hay entrenamientos guardados ni una review disponible.\n")
        } else {
            sb.append("\nHistorial reciente (del más antiguo al más reciente):\n")
            recent.forEach { sb.append("- ").append(it.toHistoryLine()).append('\n') }
            trendSummary(recent)?.let { sb.append(it).append('\n') }
            val latest = recent.last()
            sb.append("\nReview más reciente (${latest.date}): ${latest.overallScore}/10 — ${latest.headline}\n")
            latest.metricGroups().forEach { (title, items) ->
                sb.append("$title: ").append(items.joinToString(", ") { "${it.first}: ${it.second}" }).append('\n')
            }
            latest.sections.forEach {
                sb.append("- ${it.title} ${it.score}/10: ${it.review}\n")
            }
            if (latest.comparison.isNotBlank()) sb.append("Comparativa de la review: ${latest.comparison}\n")
            if (latest.suggestions.isNotEmpty()) {
                sb.append("Sugerencias de la review: ${latest.suggestions.joinToString("; ")}\n")
            }
            if (latest.followUpQuestions.isNotEmpty()) {
                sb.append("Preguntas de seguimiento: ${latest.followUpQuestions.joinToString("; ")}\n")
            }
        }
        return sb.toString()
    }

    /** JSON schema (Gemini response schema subset) the model must follow. */
    const val RESPONSE_SCHEMA = """
{
  "type": "OBJECT",
  "properties": {
    "date": {"type": "STRING", "nullable": true, "description": "YYYY-MM-DD si aparece en la captura"},
    "type": {"type": "STRING", "description": "Carrera, Fuerza, etc."},
    "metrics": {
      "type": "OBJECT",
      "properties": {
        "distanceKm": {"type": "NUMBER", "nullable": true},
        "durationMin": {"type": "NUMBER", "nullable": true},
        "paceSecPerKm": {"type": "INTEGER", "nullable": true},
        "avgHr": {"type": "INTEGER", "nullable": true},
        "maxHr": {"type": "INTEGER", "nullable": true},
        "cadence": {"type": "INTEGER", "nullable": true},
        "strideM": {"type": "NUMBER", "nullable": true},
        "contactMs": {"type": "INTEGER", "nullable": true},
        "verticalOscCm": {"type": "NUMBER", "nullable": true},
        "powerW": {"type": "INTEGER", "nullable": true},
        "tempC": {"type": "INTEGER", "nullable": true},
        "aerobicTe": {"type": "NUMBER", "nullable": true},
        "bodyBatteryImpact": {"type": "INTEGER", "nullable": true},
        "verticalRatioPct": {"type": "NUMBER", "nullable": true},
        "maxPowerW": {"type": "INTEGER", "nullable": true},
        "maxCadence": {"type": "INTEGER", "nullable": true},
        "ascentM": {"type": "INTEGER", "nullable": true},
        "anaerobicTe": {"type": "NUMBER", "nullable": true},
        "totalKcal": {"type": "INTEGER", "nullable": true}
      }
    },
    "overallScore": {"type": "INTEGER"},
    "headline": {"type": "STRING"},
    "sections": {
      "type": "ARRAY",
      "items": {
        "type": "OBJECT",
        "properties": {
          "key": {"type": "STRING"},
          "title": {"type": "STRING"},
          "score": {"type": "INTEGER"},
          "review": {"type": "STRING"}
        },
        "required": ["key", "title", "score", "review"]
      }
    },
    "comparison": {"type": "STRING"},
    "suggestions": {"type": "ARRAY", "items": {"type": "STRING"}},
    "followUpQuestions": {"type": "ARRAY", "items": {"type": "STRING"}}
  },
  "required": ["type", "metrics", "overallScore", "headline", "sections", "comparison", "suggestions", "followUpQuestions"]
}
"""

    fun userPrompt(
        history: List<WorkoutRecord>,
        todayIso: String,
        notes: String? = null,
        workoutData: String? = null,
        withImages: Boolean = false
    ): String {
        val recent = history.takeLast(HISTORY_LIMIT)
        val sb = StringBuilder()
        sb.append("Fecha de hoy: ").append(todayIso).append(".\n")
        if (workoutData.isNullOrBlank()) {
            sb.append("¿Qué opinas de mi entrenamiento? Las imágenes adjuntas son las estadísticas de Garmin Connect.\n\n")
        } else {
            sb.append("¿Qué opinas de mi entrenamiento? Estos son los datos que mi Garmin sincronizó con Health Connect; para distancia, duración, ritmo y FC son la fuente fiable.\n")
            if (withImages) sb.append("Las imágenes adjuntas son capturas de Garmin Connect del mismo entrenamiento: úsalas solo para las métricas que faltan abajo (zancada, contacto con el suelo, oscilación, TE, Body Battery, temperatura). Si una cifra choca con Health Connect, prevalece Health Connect.\n")
            sb.append(workoutData.trim()).append("\n\n")
        }
        if (recent.isEmpty()) {
            sb.append("HISTORIAL: sin entrenamientos previos registrados.\n")
        } else {
            sb.append("HISTORIAL (del más antiguo al más reciente):\n")
            recent.forEach { sb.append("- ").append(it.toHistoryLine()).append('\n') }
            trendSummary(recent)?.let { sb.append('\n').append(it).append('\n') }
        }
        if (!notes.isNullOrBlank()) {
            sb.append("\nMis sensaciones de hoy: ").append(notes.trim()).append('\n')
        }
        return sb.toString()
    }

    /** Averages of the last runs, so the model compares against numbers instead of guessing. */
    fun trendSummary(history: List<WorkoutRecord>): String? {
        val window = history.takeLast(TREND_WINDOW)
        if (window.isEmpty()) return null
        val items = mutableListOf<String>()
        window.mapNotNull { it.paceSecPerKm }.averageOrNull()?.let {
            items += "ritmo medio ${WorkoutRecord.formatPace(it.toInt())}/km"
        }
        window.mapNotNull { it.avgHr }.averageOrNull()?.let { items += "FC media ${it.toInt()} ppm" }
        window.mapNotNull { it.cadence }.averageOrNull()?.let { items += "cadencia ${it.toInt()} ppm" }
        window.mapNotNull { it.contactMs }.averageOrNull()?.let { items += "contacto suelo ${it.toInt()} ms" }
        window.mapNotNull { it.distanceKm }.averageOrNull()?.let {
            items += String.format(Locale.US, "distancia %.1f km", it)
        }
        window.map { it.overallScore }.averageOrNull()?.let {
            items += String.format(Locale.US, "nota general %.1f", it)
        }
        if (items.isEmpty()) return null
        return "PROMEDIOS de las últimas ${window.size} sesiones: ${items.joinToString(", ")}."
    }

    private fun List<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else map { it.toDouble() }.average()
}
