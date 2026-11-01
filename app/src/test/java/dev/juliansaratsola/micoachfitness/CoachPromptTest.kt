package dev.juliansaratsola.micoachfitness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoachPromptTest {
    private val today = LocalDate.of(2026, 10, 31)

    private fun record(date: String, pace: Int, hr: Int, points: Int = 80) = WorkoutRecord(
        date = date, type = "Carrera", distanceKm = 7.91, durationMin = 41.0,
        paceSecPerKm = pace, avgHr = hr, maxHr = 171, cadence = 180, strideM = 1.07,
        contactMs = 245, verticalOscCm = 8.5, powerW = 288, tempC = 26, aerobicTe = 3.0,
        bodyBatteryImpact = -7, puntosEsfuerzo = points
    )

    private fun legacyRecord(date: String, pace: Int, hr: Int, score: Int) = record(date, pace, hr).copy(
        overallScore = score, headline = "ok", sections = listOf(SectionReview("cardio", "Cardio", score, "bien"))
    )

    private fun datedLines(text: String) = text.lines().filter { Regex("^- \\d{4}-\\d{2}-\\d{2} \\|").containsMatchIn(it) }

    @Test
    fun formatsPace() {
        assertEquals("5:12", WorkoutRecord.formatPace(312))
        assertEquals("-", WorkoutRecord.formatPace(null))
    }

    @Test
    fun emptyHistoryIsStated() {
        val context = HistoricalContext.build(emptyList(), today)
        val prompt = CoachPrompt.userPrompt(context, "2026-10-31") + CoachPrompt.reviewSystemInstruction(emptyList(), today)
        assertTrue(prompt.contains("sin entrenamientos previos"))
        assertTrue(prompt.contains("FORM SCORE ACTUAL: 0"))
    }

    @Test
    fun historicalContextIsInjectedAsIs() {
        val history = listOf(record("2026-10-17", 298, 157), record("2026-10-19", 312, 157, points = 95))
        val context = HistoricalContext.build(history, today)
        val prompt = CoachPrompt.userPrompt(context, "2026-10-31", "tibias bien")
        assertTrue(prompt.contains(context.trim()))
        assertTrue(prompt.contains("esfuerzo 95 pts"))
        assertTrue(CoachPrompt.reviewSystemInstruction(history, today).contains("2026-10-19"))
        assertTrue(prompt.contains("ritmo 5:12/km"))
        assertTrue(prompt.contains("Fecha de hoy: 2026-10-31."))
        assertTrue(prompt.contains("Mis sensaciones de hoy: tibias bien"))
    }

    @Test
    fun missingFeelingsAreNotInvented() {
        listOf(null, "", "   ").forEach { notes ->
            val prompt = CoachPrompt.userPrompt("FORM SCORE ACTUAL: 0", "2026-10-31", notes)
            assertTrue(prompt.contains("No supongas malas sensaciones"))
            assertFalse(prompt.contains("Mis sensaciones de hoy"))
        }
    }

    @Test
    fun healthConnectDataComesBeforeTheContext() {
        val prompt = CoachPrompt.userPrompt("FORM SCORE ACTUAL: 0", "2026-10-31", null, "Distancia: 8 km", withImages = true)
        assertTrue(prompt.contains("Health Connect"))
        assertTrue(prompt.contains("Las imágenes adjuntas son capturas"))
        assertTrue(prompt.indexOf("Distancia: 8 km") < prompt.indexOf("CONTEXTO HISTÓRICO"))
        assertFalse(CoachPrompt.userPrompt("x", "2026-10-31", null, "Distancia: 8 km").contains("Las imágenes adjuntas"))
    }

    @Test
    fun responseSchemaRequiresTheFiveCoachTexts() {
        val schema = JSONObject(CoachPrompt.RESPONSE_SCHEMA)
        val properties = schema.getJSONObject("properties")
        val required = schema.getJSONArray("required").let { array -> (0 until array.length()).map { array.getString(it) } }
        val keys = listOf(
            WorkoutJson.KEY_TECHNIQUE, WorkoutJson.KEY_CARDIO, WorkoutJson.KEY_POWER,
            WorkoutJson.KEY_COMPARISON, WorkoutJson.KEY_VERDICT
        )
        assertEquals(
            listOf("tecnica", "cardio_y_esfuerzo", "potencia_y_consistencia", "comparativa_meritocratica", "veredicto_trinchera"),
            keys
        )
        keys.forEach { key ->
            assertEquals(key, "STRING", properties.getJSONObject(key).getString("type"))
            assertTrue(key, key in required)
        }
        assertTrue("metrics" in required)
        listOf("overallScore", "headline", "sections", "comparison", "suggestions", "followUpQuestions")
            .forEach { assertFalse(it, properties.has(it)) }
    }

    @Test
    fun responseSchemaRequiresNullableTechnicalMetricFields() {
        val metrics = JSONObject(CoachPrompt.RESPONSE_SCHEMA)
            .getJSONObject("properties").getJSONObject("metrics")
        val required = metrics.getJSONArray("required").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        listOf(
            "cadence", "maxCadence", "strideM", "verticalRatioPct",
            "verticalOscCm", "contactMs", "powerW", "maxPowerW", "ascentM"
        ).forEach { key ->
            assertTrue(key, key in required)
            assertTrue(key, metrics.getJSONObject("properties").getJSONObject(key).getBoolean("nullable"))
        }
        assertTrue(CoachPrompt.SYSTEM_INSTRUCTION.contains("DINÁMICA DE CARRERA"))
        assertTrue(CoachPrompt.SYSTEM_INSTRUCTION.contains("nunca digas que no hay datos de dinámica"))
    }

    @Test
    fun technicalSupplementFillsMissingMetricsWithoutOverwritingDetectedValues() {
        val review = JSONObject(
            """{"metrics":{"cadence":180,"strideM":null,"contactMs":null,"verticalOscCm":null,"verticalRatioPct":null},"tecnica":"No hay datos"}"""
        )
        val supplement = JSONObject(
            """{"metrics":{"cadence":170,"strideM":0.99,"contactMs":250,"verticalOscCm":8.2,"verticalRatioPct":8.2,"maxCadence":234,"powerW":269,"maxPowerW":353,"ascentM":37},"tecnica":"Cadencia 180 ppm y zancada de 0,99 m; contacto 250 ms, oscilación 8,2 cm y ratio 8,2%."}"""
        )

        assertTrue(WorkoutJson.needsTechnicalMetricSupplement(review))
        WorkoutJson.mergeTechnicalSupplement(review, supplement)

        val metrics = review.getJSONObject("metrics")
        assertEquals(180, metrics.getInt("cadence"))
        assertEquals(0.99, metrics.getDouble("strideM"), 0.001)
        assertEquals(250, metrics.getInt("contactMs"))
        assertEquals(353, metrics.getInt("maxPowerW"))
        assertEquals(supplement.getString("tecnica"), review.getString("tecnica"))
        assertFalse(WorkoutJson.needsTechnicalMetricSupplement(review))
    }

    @Test
    fun technicalSupplementRetriesWhenNarrativeDeniesAlreadyExtractedMetrics() {
        val review = JSONObject(
            """{"metrics":{"cadence":180,"strideM":0.99,"contactMs":250,"verticalOscCm":8.2,"verticalRatioPct":8.2},"tecnica":"No hay datos de dinámicas de carrera disponibles en la captura. Imposible evaluar cadencia o zancada."}"""
        )

        assertTrue(WorkoutJson.needsTechnicalMetricSupplement(review))
    }

    @Test
    fun systemInstructionEnforcesTheVerdadPuraRules() {
        val system = CoachPrompt.SYSTEM_INSTRUCTION
        listOf(
            "Coach de la Verdad", "sin positividad tóxica", "NUNCA alabes", "consistencia previa", "Día de Trinchera",
            "resiliencia", "Form Score", "esfuerzo relativo", "No juzgues si el ritmo es lento o rápido",
            "no inventes ni cites una cifra de puntos de hoy"
        ).forEach { assertTrue(it, system.contains(it, ignoreCase = true)) }
        listOf("tecnica", "cardio_y_esfuerzo", "potencia_y_consistencia", "comparativa_meritocratica", "veredicto_trinchera")
            .forEach { assertTrue(it, system.contains(it)) }
        listOf("motivador", "nota general", "de 1 a 10", "emoji por sección").forEach { assertFalse(it, system.contains(it)) }
    }

    @Test
    fun historyIsLimited() {
        val history = (1..20).map { record("2026-09-%02d".format(it), 300, 150, 7) }
        val prompt = CoachPrompt.reviewSystemInstruction(history)
        assertEquals(HistoricalContext.SESSION_LIMIT, datedLines(prompt).size)
    }

    @Test
    fun metricGroupsListOnlyAvailableValues() {
        val base = record("2026-10-30", 328, 147, 7)
        val full = base.copy(
            contactMs = 249, verticalOscCm = 8.3, verticalRatioPct = 8.2, strideM = 1.02, powerW = 276, maxPowerW = 339
        )
        val groups = full.metricGroups().toMap()
        assertEquals("249 ms", groups.getValue("Dinámica de carrera").toMap()["Tiempo de contacto"])
        assertEquals("8.2 %", groups.getValue("Dinámica de carrera").toMap()["Ratio vertical"])
        assertEquals("339 W", groups.getValue("Potencia").toMap()["Potencia máxima"])
        assertTrue(!base.copy(powerW = null).metricGroups().toMap().containsKey("Potencia"))
    }

    @Test
    fun chatContextIncludesLatestLegacyReviewAndAvoidsInventedBenchmarks() {
        val latest = legacyRecord("2026-10-30", 328, 147, 7).copy(
            headline = "Rodaje estable",
            tecnica = "Técnica constante.",
            cardio = "Cardio controlado.",
            maxPowerW = 340,
            verticalRatioPct = 8.2,
            comparison = "Ritmo similar a la sesión anterior.",
            suggestions = listOf("Prioriza el descanso."),
            followUpQuestions = listOf("¿Cómo te sentiste?")
        )
        val prompt = CoachPrompt.chatSystemInstruction(listOf(latest), today)
        assertTrue(prompt.contains("Review más reciente (2026-10-30): 7/10 — Rodaje estable"))
        assertTrue(prompt.contains("Potencia máxima: 340 W"))
        assertTrue(prompt.contains("Ratio vertical: 8.2 %"))
        assertTrue(prompt.contains("Ritmo similar a la sesión anterior."))
        assertTrue(prompt.contains("Prioriza el descanso."))
        assertTrue(prompt.contains("Técnica: Técnica constante."))
        assertTrue(prompt.contains("Cardio: Cardio controlado."))
        assertTrue(prompt.contains("No inventes métricas, edad, peso"))
    }

    @Test
    fun chatContextIncludesTheVerdadPuraReviewAndTheFormScore() {
        val latest = record("2026-10-30", 503, 168, points = 210).copy(
            esDiaTrinchera = true,
            textoSensaciones = "mucho calor",
            verdadPura = VerdadPuraReview(
                tecnica = "Cadencia estable.",
                cardioYEsfuerzo = "FC alta sostenida.",
                veredictoTrinchera = "Día duro, terminado."
            )
        )
        val prompt = CoachPrompt.chatSystemInstruction(listOf(latest), today)
        assertTrue(prompt.contains("FORM SCORE ACTUAL: 105 (nivel 3, Recluta)"))
        assertTrue(prompt.contains("Review más reciente (2026-10-30): 210 pts de esfuerzo, día de trinchera"))
        assertTrue(prompt.contains("Técnica: Cadencia estable."))
        assertTrue(prompt.contains("Cardio y esfuerzo: FC alta sostenida."))
        assertTrue(prompt.contains("Veredicto de trinchera: Día duro, terminado."))
        assertTrue(prompt.contains("sensaciones: \"mucho calor\""))
        assertFalse(prompt.contains("Potencia y consistencia:"))
        assertFalse(prompt.contains("/10"))
    }

    @Test
    fun chatContextSharesThePhilosophyAndIsLimited() {
        val history = (1..20).map { record("2026-10-%02d".format(it), 300, 150) }
        val prompt = CoachPrompt.chatSystemInstruction(history, today)
        assertEquals(CoachPrompt.HISTORY_LIMIT, datedLines(prompt).size)
        assertTrue(prompt.contains("NUNCA alabes"))
        assertTrue(prompt.contains("Coach de la Verdad"))
    }

    @Test
    fun chatContextWithoutWorkoutsSaysSo() {
        val prompt = CoachPrompt.chatSystemInstruction(emptyList(), today)
        assertTrue(prompt.contains("Todavía no hay entrenamientos guardados"))
        assertFalse(prompt.contains("Review más reciente"))
    }

    @Test
    fun chatContextIncludesExactMainAnalysisJson() {
        val json = """{"puntuacion_general":8,"tecnica":"cadencia estable"}"""
        val prompt = CoachPrompt.chatSystemInstruction(emptyList(), latestAnalysisJson = json)
        assertTrue(prompt.contains("ANÁLISIS PRINCIPAL EN JSON"))
        assertTrue(prompt.contains(json))
    }
}
