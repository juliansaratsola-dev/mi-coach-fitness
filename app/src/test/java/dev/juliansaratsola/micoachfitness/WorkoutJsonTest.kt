package dev.juliansaratsola.micoachfitness

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutJsonTest {
    private fun legacy() = JSONObject(
        """
        {"date":"2026-10-19","type":"Carrera",
         "metrics":{"distanceKm":7.91,"durationMin":41,"paceSecPerKm":312,"avgHr":157,"maxHr":171,"cadence":180},
         "overallScore":9,"headline":"Buen rodaje",
         "sections":[{"key":"cardio","title":"Cardio","score":9,"review":"ok"}],
         "comparison":"Mejor que la anterior","suggestions":["Descansa","Hidrátate"],"followUpQuestions":["¿Cómo estás?"]}
        """.trimIndent()
    )

    private fun stored(points: Any, trench: Boolean = false, feelings: String = "") = legacy().apply {
        put(WorkoutJson.KEY_EFFORT_POINTS, points)
        put(WorkoutJson.KEY_TRENCH_DAY, trench)
        put(WorkoutJson.KEY_FEELINGS, feelings)
    }

    @Test
    fun legacyWorkoutIsBackfilledWithEffortFromItsMetrics() {
        val record = WorkoutJson.parse(legacy())
        val expected = EffortCalculator.evaluateSession(41.0, 157, 171, null, "", 312)
        assertTrue(record.puntosEsfuerzo > 0)
        assertEquals(expected.points, record.puntosEsfuerzo)
        assertFalse(record.esDiaTrinchera)
        assertEquals("", record.textoSensaciones)
    }

    @Test
    fun legacyReviewFieldsSurvive() {
        val record = WorkoutJson.parse(legacy())
        assertTrue(record.hasLegacyReview())
        assertEquals(9, record.overallScore)
        assertEquals("Buen rodaje", record.headline)
        assertEquals(1, record.sections.size)
        assertEquals(listOf("Descansa", "Hidrátate"), record.suggestions)
        assertEquals(312, record.paceSecPerKm)
        assertTrue(record.verdadPura.isEmpty)
    }

    @Test
    fun mainBranchReviewFieldsRemainReadable() {
        val json = JSONObject(
            """{"puntuacion_general":8,"headline":"Rodaje estable","tecnica":"Cadencia estable","cardio":"FC controlada","potencia_rendimiento":"Ritmo parejo","comparativa_poblacional":"Sin referencia fiable","sugerencias_proximo_entreno":["Descansa"]}"""
        )
        val record = WorkoutJson.parse(json)
        assertEquals(8, record.overallScore)
        assertEquals("Cadencia estable", record.tecnica)
        assertEquals("FC controlada", record.cardio)
        assertEquals("Ritmo parejo", record.potenciaRendimiento)
        assertEquals("Sin referencia fiable", record.comparativaPoblacional)
        assertEquals(listOf("Descansa"), record.suggestions)
        assertTrue(record.hasLegacyReview())
    }

    @Test
    fun userAgeOnlyAffectsTheBackfill() {
        assertTrue(WorkoutJson.parse(legacy(), 60).puntosEsfuerzo > WorkoutJson.parse(legacy(), 20).puntosEsfuerzo)
        assertEquals(WorkoutJson.parse(stored(123), 20).puntosEsfuerzo, WorkoutJson.parse(stored(123), 60).puntosEsfuerzo)
    }

    @Test
    fun storedEffortIsKeptAsIs() {
        val record = WorkoutJson.parse(stored(123, trench = true, feelings = "mucho calor"))
        assertEquals(123, record.puntosEsfuerzo)
        assertTrue(record.esDiaTrinchera)
        assertEquals("mucho calor", record.textoSensaciones)
    }

    @Test
    fun storedEffortIsNeverNegative() {
        assertEquals(0, WorkoutJson.parse(stored(-5)).puntosEsfuerzo)
    }

    @Test
    fun aStoredZeroIsNotBackfilled() {
        assertEquals(0, WorkoutJson.parse(stored(0)).puntosEsfuerzo)
    }

    @Test
    fun jsonNullsAreNotTurnedIntoTheTextNull() {
        val json = legacy().apply {
            put("headline", JSONObject.NULL)
            put(WorkoutJson.KEY_FEELINGS, JSONObject.NULL)
            put(WorkoutJson.KEY_TECHNIQUE, JSONObject.NULL)
            put("suggestions", org.json.JSONArray().put(JSONObject.NULL).put("Descansa"))
        }
        val record = WorkoutJson.parse(json)
        assertEquals("", record.headline)
        assertEquals("", record.textoSensaciones)
        assertEquals("", record.verdadPura.tecnica)
        assertEquals(listOf("Descansa"), record.suggestions)
    }

    @Test
    fun invalidWorkoutJsonStillParses() {
        val record = WorkoutJson.parse(JSONObject("{}"))
        assertEquals("", record.date)
        assertEquals("Carrera", record.type)
        assertEquals(0, record.overallScore)
        assertTrue(record.puntosEsfuerzo > 0)
    }

    @Test
    fun verdadPuraTextsAreReadAndTrimmed() {
        val json = JSONObject().apply {
            put(WorkoutJson.KEY_TECHNIQUE, "  Cadencia estable.  ")
            put(WorkoutJson.KEY_CARDIO, "FC alta.")
            put(WorkoutJson.KEY_POWER, "Ritmo parejo.")
            put(WorkoutJson.KEY_COMPARISON, "Más volumen que tu media.")
            put(WorkoutJson.KEY_VERDICT, "Día duro, bien cerrado.")
        }
        val review = WorkoutJson.parse(json).verdadPura
        assertEquals("Cadencia estable.", review.tecnica)
        assertEquals("FC alta.", review.cardioYEsfuerzo)
        assertEquals("Ritmo parejo.", review.potenciaYConsistencia)
        assertEquals("Más volumen que tu media.", review.comparativaMeritocratica)
        assertEquals("Día duro, bien cerrado.", review.veredictoTrinchera)
        assertFalse(WorkoutJson.parse(json).hasLegacyReview())
    }

    @Test
    fun aValidPastDateIsKept() {
        val raw = WorkoutJson.normalizeDate(JSONObject("""{"date":" 2026-10-28 "}"""), "2026-10-31")
        assertEquals("2026-10-28", raw.getString("date"))
        assertEquals("2026-10-31", WorkoutJson.normalizeDate(JSONObject("""{"date":"2026-10-31"}"""), "2026-10-31").getString("date"))
    }

    @Test
    fun missingMalformedOrFutureDatesFallBack() {
        listOf("""{}""", """{"date":null}""", """{"date":"  "}""", """{"date":"28 oct 2026"}""", """{"date":"2026-11-02"}""")
            .forEach { json ->
                assertEquals(json, "2026-10-31", WorkoutJson.normalizeDate(JSONObject(json), "2026-10-31").getString("date"))
            }
    }

    @Test
    fun aGeminiResponseBecomesAScoredRecord() {
        val response = """
            {"date":"2026-10-30","type":"Carrera",
             "metrics":{"distanceKm":6.2,"durationMin":52,"paceSecPerKm":503,"avgHr":168,"maxHr":181},
             "tecnica":"Cadencia estable.","cardio_y_esfuerzo":"FC alta sostenida.",
             "potencia_y_consistencia":"Ritmo parejo.","comparativa_meritocratica":"Más esfuerzo que tu media.",
             "veredicto_trinchera":"Día duro, terminado."}
        """.trimIndent()
        val raw = WorkoutJson.parseReviewResponse(response)
        assertTrue(WorkoutJson.hasReview(raw))
        WorkoutJson.normalizeDate(raw, "2026-10-30")
        WorkoutJson.applyEffort(raw, "Hacía calor y estaba cansado", null)
        val record = WorkoutJson.parse(raw)
        assertTrue(record.puntosEsfuerzo > EffortCalculator.HIGH_EFFORT_THRESHOLD)
        assertTrue(record.esDiaTrinchera)
        assertEquals("Hacía calor y estaba cansado", record.textoSensaciones)
        assertEquals("Día duro, terminado.", record.verdadPura.veredictoTrinchera)
        assertFalse(record.hasLegacyReview())
    }

    @Test
    fun healthConnectMetricsAreStoredAndShownAsSessionData() {
        val raw = JSONObject(
            """{"date":"2026-10-30","type":"Carrera","metrics":{"durationMin":41,"minHr":132,"avgHr":157,"maxHr":171,"avgSpeedKmh":11.4,"minSpeedKmh":2.1,"maxSpeedKmh":13.8,"activeKcal":467,"totalKcal":524}}"""
        )
        val record = WorkoutJson.parse(raw)
        assertEquals(132, record.minHr)
        assertEquals(467, record.activeKcal)
        assertEquals(11.4, record.avgSpeedKmh!!, 0.001)
        assertEquals(2.1, record.minSpeedKmh!!, 0.001)
        assertEquals(13.8, record.maxSpeedKmh!!, 0.001)
        val groups = record.metricGroups().flatMap { it.second }.toMap()
        assertEquals("132 ppm", groups["FC mínima"])
        assertEquals("11.4 km/h", groups["Velocidad media"])
        assertEquals("467 kcal", groups["Calorías activas"])
    }

    @Test
    fun relativePowerUsesLocalWeightOnlyWhenRequestedForDisplay() {
        val raw = JSONObject("""{"metrics":{"powerW":276,"maxPowerW":339}}""")
        val record = WorkoutJson.parse(raw, userWeightKg = 75.0)

        val ordinaryGroups = record.metricGroups().flatMap { it.second }.toMap()
        val displayGroups = record.metricGroups(includePowerPerKg = true).flatMap { it.second }.toMap()
        assertFalse(ordinaryGroups.containsKey("Potencia media (W/kg)"))
        assertEquals("3.68 W/kg", displayGroups["Potencia media (W/kg)"])
        assertEquals("4.52 W/kg", displayGroups["Potencia máxima (W/kg)"])
        assertFalse(raw.has("bodyWeightKg"))
    }

    @Test
    fun applyEffortWritesTheThreePersistedKeys() {
        val raw = JSONObject("""{"date":"2026-10-30","type":"Carrera","metrics":{"durationMin":50,"avgHr":160,"maxHr":175}}""")
        WorkoutJson.applyEffort(raw, "Mucho calor y piernas pesadas", null)
        val record = WorkoutJson.parse(raw)
        assertTrue(raw.getInt(WorkoutJson.KEY_EFFORT_POINTS) > EffortCalculator.HIGH_EFFORT_THRESHOLD)
        assertTrue(raw.getBoolean(WorkoutJson.KEY_TRENCH_DAY))
        assertEquals("Mucho calor y piernas pesadas", raw.getString(WorkoutJson.KEY_FEELINGS))
        assertEquals(raw.getInt(WorkoutJson.KEY_EFFORT_POINTS), record.puntosEsfuerzo)
        assertTrue(record.esDiaTrinchera)
        assertEquals("Mucho calor y piernas pesadas", record.textoSensaciones)
    }

    @Test
    fun sufferingWordsAloneDoNotMakeATrenchDay() {
        val raw = JSONObject("""{"type":"Carrera","metrics":{"durationMin":15,"avgHr":110,"maxHr":120}}""")
        WorkoutJson.applyEffort(raw, "dolor y calor", null)
        assertFalse(raw.getBoolean(WorkoutJson.KEY_TRENCH_DAY))
    }

    @Test
    fun applyEffortWithoutNotesOrHeartRateStillScores() {
        val raw = JSONObject("""{"type":"Fuerza","metrics":{"durationMin":30}}""")
        WorkoutJson.applyEffort(raw, null, null)
        assertTrue(raw.getInt(WorkoutJson.KEY_EFFORT_POINTS) > 0)
        assertFalse(raw.getBoolean(WorkoutJson.KEY_TRENCH_DAY))
        assertEquals("", raw.getString(WorkoutJson.KEY_FEELINGS))
        assertTrue(WorkoutJson.parse(raw).toHistoryLine().contains("estimado, sin FC"))
    }

    @Test
    fun applyEffortSurvivesMissingMetrics() {
        val raw = JSONObject("""{"type":"Carrera"}""")
        WorkoutJson.applyEffort(raw, "   ", 30)
        assertTrue(raw.getInt(WorkoutJson.KEY_EFFORT_POINTS) > 0)
        assertEquals("", raw.getString(WorkoutJson.KEY_FEELINGS))
    }

    @Test
    fun longFeelingsAreCappedBeforeBeingStored() {
        val raw = JSONObject("""{"type":"Carrera","metrics":{"durationMin":40,"avgHr":150}}""")
        WorkoutJson.applyEffort(raw, "  " + "a".repeat(900) + "  ", null)
        assertEquals(WorkoutJson.MAX_FEELINGS_CHARS, raw.getString(WorkoutJson.KEY_FEELINGS).length)
    }

    @Test
    fun parsesPlainJsonResponses() {
        val json = WorkoutJson.parseReviewResponse("""{"type":"Carrera","tecnica":"ok"}""")
        assertEquals("ok", json.getString("tecnica"))
    }

    @Test
    fun parsesFencedAndSurroundedJsonResponses() {
        assertEquals("Carrera", WorkoutJson.parseReviewResponse("```json\n{\"type\":\"Carrera\"}\n```").getString("type"))
        assertEquals("Carrera", WorkoutJson.parseReviewResponse("Aquí tienes:\n{\"type\":\"Carrera\"}\nFin").getString("type"))
    }

    @Test(expected = JSONException::class)
    fun responseWithoutJsonIsRejected() {
        WorkoutJson.parseReviewResponse("Lo siento, no puedo ayudarte con eso.")
    }

    @Test(expected = JSONException::class)
    fun truncatedJsonIsRejected() {
        WorkoutJson.parseReviewResponse("""{"type":"Carrera","tecnica":"ok""")
    }

    @Test
    fun aReviewNeedsAtLeastOneText() {
        assertFalse(WorkoutJson.hasReview(JSONObject("{}")))
        assertFalse(WorkoutJson.hasReview(JSONObject("""{"tecnica":"   "}""")))
        assertTrue(WorkoutJson.hasReview(JSONObject("""{"veredicto_trinchera":"Dura"}""")))
    }
}
