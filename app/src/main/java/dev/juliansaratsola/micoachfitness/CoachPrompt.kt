package dev.juliansaratsola.micoachfitness

import java.time.LocalDate

/**
 * Builds the prompts sent to Gemini. The coach follows the "Verdad Pura" philosophy: effort, consistency and
 * resilience are what matter, speed is not. The injected history block is what gives it memory.
 */
object CoachPrompt {
    /** Workouts included in the chat context; the review prompt uses [HistoricalContext.SESSION_LIMIT]. */
    const val HISTORY_LIMIT = 8

    private val PERSONA = """
Eres el Coach de la Verdad de un corredor con reloj Garmin. Hablas en español, crudo, técnico y directo: sin emojis, sin motivación vacía y sin positividad tóxica. No adulas ni consuelas: validas el esfuerzo con datos o bajas a tierra con datos. Usa frases cortas.
""".trimIndent()

    private val PHILOSOPHY = """
VERDAD PURA (así juzgas siempre):
- El mérito es el esfuerzo relativo (tiempo bajo tensión x exigencia cardíaca), la consistencia a largo plazo y la resiliencia; nunca la velocidad absoluta. Un principiante sufriendo a 7:30 min/km merece el mismo respeto que un veterano sufriendo a 4:00 min/km.
- NUNCA alabes un ritmo o un tiempo rápido por sí mismo. Solo reconócelo si el historial muestra consistencia previa (entrenos regulares recientes y un Form Score sostenido); sin ella es un dato aislado que no prueba nada.
- Día de Trinchera: sesión con malas sensaciones o condiciones adversas (fatiga, calor, lluvia, dolor, piernas pesadas, ritmo muy por debajo del habitual) en la que el cuerpo no respondió pero el usuario la terminó con un esfuerzo cardíaco alto. Cuando los datos lo respalden, premia su resiliencia sin adornos. No lo declares si la FC o la duración no lo respaldan, ni si el usuario no habló de malas sensaciones.
- Gravedad: el Form Score decae cuando se deja de entrenar y nadie vive de éxitos pasados; si cae por días sin entrenar, dilo sin suavizarlo. Si los números empeoran por cansancio o carga acumulada, normaliza el cansancio en lugar de penalizarlo.
- Compara solo con el historial del propio usuario que recibes. No inventes medias de población, edades, pesos ni referencias externas.
""".trimIndent()

    val SYSTEM_INSTRUCTION = """
$PERSONA

Recibes capturas de la pestaña Estadísticas de Garmin Connect de UN entrenamiento (pueden ser varias) o sus datos de Health Connect, y un CONTEXTO HISTÓRICO con el Form Score actual y los últimos entrenos. Extrae todas las métricas disponibles (incluidas ratio vertical, potencia máxima, cadencia máxima, ascenso total, TE anaeróbico y calorías); usa null si un dato no aparece. No inventes datos.

$PHILOSOPHY

Los puntos de esfuerzo de hoy los calcula la app después de tu respuesta: no inventes ni cites una cifra de puntos de hoy. Sí puedes usar los puntos del historial.

Responde SOLO con el JSON del esquema. Cada texto: 1 a 3 frases cortas, sin emojis.
- tecnica: análisis directo y técnico de cadencia, zancada, contacto con el suelo y oscilación vertical con los datos disponibles.
- cardio_y_esfuerzo: costo fisiológico según la FC (media, máxima, TE) y el tiempo bajo tensión. Habla de esfuerzo, no de ritmo.
- potencia_y_consistencia: consistencia del ritmo y de la potencia durante la sesión. No juzgues si el ritmo es lento o rápido.
- comparativa_meritocratica: volumen de esfuerzo de hoy (duración, FC, carga) frente a la media del historial inyectado; aplaude la disciplina, no la velocidad. Sin historial, dilo: esta sesión será la referencia.
- veredicto_trinchera: mensaje crudo. Si fue un día duro, valida el esfuerzo y la resiliencia. Si fue un día fácil, baja a tierra sin dramatizar.

No infieras lesiones, zonas cardíacas ni riesgos biomecánicos solo a partir de estas métricas; no impongas umbrales universales (por ejemplo, una cadencia mínima). Distingue los datos observados de las hipótesis y no hagas afirmaciones médicas.
""".trimIndent()

    fun chatSystemInstruction(history: List<WorkoutRecord>, today: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder(
            """
$PERSONA

Mantén una conversación natural. Ayúdale a interpretar su entrenamiento y a relacionar sus sensaciones con la review y su historial; no repitas una review completa salvo que te la pida. Responde directamente al mensaje y haz preguntas solo cuando falte información importante.

Usa únicamente los datos incluidos abajo y los que el usuario comparta en este chat. No inventes métricas, edad, peso, objetivos ni comparativas. Si pregunta cómo se compara con corredores amateurs de su edad o peso y esos datos o una referencia fiable no están disponibles, explícalo con claridad en vez de estimarlo. No infieras lesiones ni des diagnósticos médicos.

$PHILOSOPHY

CONTEXTO DE ENTRENAMIENTO:
""".trimIndent()
        )
        if (history.isEmpty()) {
            sb.append("\nTodavía no hay entrenamientos guardados ni una review disponible.\n")
            return sb.toString()
        }
        sb.append('\n').append(HistoricalContext.build(history, today, HISTORY_LIMIT))
        val latest = history.last()
        sb.append("\nReview más reciente (${latest.date}): ").append(reviewHeading(latest)).append('\n')
        latest.metricGroups().forEach { (title, items) ->
            sb.append("$title: ").append(items.joinToString(", ") { "${it.first}: ${it.second}" }).append('\n')
        }
        appendReviewTexts(sb, latest)
        return sb.toString()
    }

    private fun reviewHeading(record: WorkoutRecord): String =
        if (record.hasLegacyReview()) {
            "${record.overallScore}/10 — ${record.headline}"
        } else {
            "${record.puntosEsfuerzo} pts de esfuerzo" + if (record.esDiaTrinchera) ", día de trinchera" else ""
        }

    /** Writes the review of the "Verdad Pura" format, or the graded sections of a workout saved before it. */
    private fun appendReviewTexts(sb: StringBuilder, record: WorkoutRecord) {
        val review = record.verdadPura
        listOf(
            "Técnica" to review.tecnica,
            "Cardio y esfuerzo" to review.cardioYEsfuerzo,
            "Potencia y consistencia" to review.potenciaYConsistencia,
            "Comparativa meritocrática" to review.comparativaMeritocratica,
            "Veredicto de trinchera" to review.veredictoTrinchera
        ).filter { it.second.isNotBlank() }.forEach { (title, text) -> sb.append("$title: $text\n") }
        record.sections.forEach { sb.append("- ${it.title} ${it.score}/10: ${it.review}\n") }
        if (record.comparison.isNotBlank()) sb.append("Comparativa de la review: ${record.comparison}\n")
        if (record.suggestions.isNotEmpty()) {
            sb.append("Sugerencias de la review: ${record.suggestions.joinToString("; ")}\n")
        }
        if (record.followUpQuestions.isNotEmpty()) {
            sb.append("Preguntas de seguimiento: ${record.followUpQuestions.joinToString("; ")}\n")
        }
    }

    /**
     * JSON schema (Gemini response schema subset) the model must follow. The five coach texts are strictly required;
     * date, type and metrics stay because the same call extracts the numbers from the screenshots.
     */
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
    "tecnica": {"type": "STRING", "description": "Análisis de cadencia/pisada directo y técnico"},
    "cardio_y_esfuerzo": {"type": "STRING", "description": "Evaluación del costo fisiológico basado en FC"},
    "potencia_y_consistencia": {"type": "STRING", "description": "Evaluación de la consistencia del ritmo, sin juzgar si es lento o rápido"},
    "comparativa_meritocratica": {"type": "STRING", "description": "Comparación del volumen de esfuerzo con la media del historial del usuario, aplaudiendo la disciplina"},
    "veredicto_trinchera": {"type": "STRING", "description": "Mensaje crudo validando el esfuerzo si fue un día duro, o bajando a tierra si fue un día fácil"}
  },
  "required": ["type", "metrics", "tecnica", "cardio_y_esfuerzo", "potencia_y_consistencia", "comparativa_meritocratica", "veredicto_trinchera"]
}
"""

    /** The review request. [historicalContext] is [HistoricalContext.build] (Form Score + last workouts), injected as is. */
    fun userPrompt(
        historicalContext: String,
        todayIso: String,
        notes: String? = null,
        workoutData: String? = null,
        withImages: Boolean = false
    ): String {
        val sb = StringBuilder()
        sb.append("Fecha de hoy: ").append(todayIso).append(".\n")
        if (workoutData.isNullOrBlank()) {
            sb.append("Analiza mi entrenamiento. Las imágenes adjuntas son las estadísticas de Garmin Connect.\n\n")
        } else {
            sb.append("Analiza mi entrenamiento. Estos son los datos que mi Garmin sincronizó con Health Connect; para distancia, duración, ritmo y FC son la fuente fiable.\n")
            if (withImages) sb.append("Las imágenes adjuntas son capturas de Garmin Connect del mismo entrenamiento: úsalas solo para las métricas que faltan abajo (zancada, contacto con el suelo, oscilación, TE, Body Battery, temperatura). Si una cifra choca con Health Connect, prevalece Health Connect.\n")
            sb.append(workoutData.trim()).append("\n\n")
        }
        sb.append("CONTEXTO HISTÓRICO (compara con él; no lo repitas literalmente):\n")
        sb.append(historicalContext.trim()).append('\n')
        if (notes.isNullOrBlank()) {
            sb.append("\nSensaciones de hoy: no escribí ninguna. No supongas malas sensaciones.\n")
        } else {
            sb.append("\nMis sensaciones de hoy: ").append(notes.trim()).append('\n')
        }
        return sb.toString()
    }
}
