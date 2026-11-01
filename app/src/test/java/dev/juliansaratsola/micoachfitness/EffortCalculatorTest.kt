package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortCalculatorTest {
    private fun score(duration: Double? = 60.0, avg: Int? = 150, max: Int? = 170, age: Int? = 35) =
        EffortCalculator.calculateEffortScore(duration, avg, max, age)

    @Test
    fun missingHeartRateUsesStandardEffort() {
        assertEquals(80.0, score(duration = 40.0, avg = null, max = null), 1e-9)
        assertEquals(80.0, score(duration = 40.0, avg = null, max = 170), 1e-9)
        assertEquals(80.0, score(duration = 40.0, avg = 0, max = null), 1e-9)
    }

    @Test
    fun missingDurationUsesStandardDuration() {
        val standard = EffortCalculator.STANDARD_DURATION_MIN * EffortCalculator.STANDARD_WEIGHT_PER_MINUTE
        assertEquals(standard, score(duration = null, avg = null, max = null), 1e-9)
        assertEquals(score(duration = 30.0), score(duration = null), 1e-9)
        assertEquals(score(duration = 30.0), score(duration = -5.0), 1e-9)
        assertEquals(score(duration = 30.0), score(duration = Double.NaN), 1e-9)
    }

    @Test
    fun nothingAtAllStillReturnsAScore() {
        assertEquals(60.0, EffortCalculator.calculateEffortScore(null, null, null, null), 1e-9)
    }

    @Test
    fun absurdValuesAreIgnoredOrCapped() {
        assertEquals(score(avg = null, max = null), score(avg = 400, max = 500), 1e-9)
        assertEquals(score(avg = 150, max = null), score(avg = 150, max = 400), 1e-9)
        assertEquals(score(avg = 150, max = null), score(avg = 150, max = 120), 1e-9)
        assertEquals(score(duration = 1440.0), score(duration = 100_000.0), 1e-9)
        assertEquals(score(age = null), score(age = 3), 1e-9)
        assertEquals(score(age = null), score(age = 250), 1e-9)
    }

    @Test
    fun effortGrowsWithAverageHeartRate() {
        var previous = -1.0
        for (avg in 85..185) {
            val current = score(avg = avg, max = 190)
            assertTrue("avg=$avg dropped: $previous -> $current", current >= previous)
            previous = current
        }
        assertTrue(score(avg = 175) > 2 * score(avg = 110))
    }

    @Test
    fun effortGrowsLinearlyWithDuration() {
        assertEquals(2 * score(duration = 30.0), score(duration = 60.0), 1e-9)
    }

    @Test
    fun sameHeartRateIsHarderForOlderAthletes() {
        assertTrue(score(age = 60) > score(age = 20))
    }

    @Test
    fun steadyEffortLandsInItsZone() {
        // 30 years old: max HR 190, so 143 bpm is 75 % (middle of zone 3).
        val perMinute = score(duration = 60.0, avg = 143, max = null, age = 30) / 60.0
        assertTrue("was $perMinute", perMinute in 2.7..3.3)
        // 85 % of 190 is the middle of zone 4.
        val hard = score(duration = 60.0, avg = 162, max = null, age = 30) / 60.0
        assertTrue("was $hard", hard in 3.7..4.3)
    }

    @Test
    fun zoneMinutesAddUpToTheDuration() {
        val zones = EffortCalculator.estimateZoneMinutes(45.0, 148, 176, 35)
        assertEquals(45.0, zones.total, 1e-6)
        assertEquals(5, zones.zones.size)
        assertTrue(zones.zones.all { it >= 0.0 })
        val standard = EffortCalculator.estimateZoneMinutes(45.0, null, null, null)
        assertEquals(45.0, standard.zones[1], 1e-9)
    }

    @Test
    fun suffocatingWordsWithHighEffortAreATrenchDay() {
        val result = EffortCalculator.evaluateTrenchDay("Piernas pesadas y mucho calor", 150.0, 420)
        assertTrue(result.isTrenchDay)
        assertEquals(1.2, result.multiplier, 1e-9)
    }

    @Test
    fun everyDocumentedKeywordIsDetected() {
        listOf(
            "pesado", "cansancio", "dolor", "calor", "lluvia", "Estaba CANSADO", "me dolía la rodilla",
            "hizo un calor horrible", "llovió todo el rato", "mala sensación", "fatiga acumulada"
        ).forEach {
            assertTrue("not detected: $it", EffortCalculator.evaluateTrenchDay(it, 150.0).isTrenchDay)
        }
    }

    @Test
    fun lowEffortIsNeverATrenchDay() {
        val result = EffortCalculator.evaluateTrenchDay("muy pesado, con lluvia y dolor", 40.0)
        assertFalse(result.isTrenchDay)
        assertEquals(1.0, result.multiplier, 1e-9)
    }

    @Test
    fun highEffortWithoutSufferingIsNotATrenchDay() {
        listOf(null, "", "   ", "me sentí genial", "piernas ligeras, buen día").forEach {
            val result = EffortCalculator.evaluateTrenchDay(it, 300.0)
            assertFalse("flagged: $it", result.isTrenchDay)
            assertEquals(1.0, result.multiplier, 1e-9)
        }
    }

    @Test
    fun thresholdIsInclusive() {
        val limit = EffortCalculator.HIGH_EFFORT_THRESHOLD
        assertTrue(EffortCalculator.evaluateTrenchDay("calor", limit).isTrenchDay)
        assertFalse(EffortCalculator.evaluateTrenchDay("calor", limit - 0.1).isTrenchDay)
        assertTrue(EffortCalculator.evaluateTrenchDay("calor", 60.0, highEffortThreshold = 50.0).isTrenchDay)
    }

    @Test
    fun negatedSufferingDoesNotCount() {
        listOf(
            "sin dolor", "no hizo calor", "no sentí nada de dolor", "sin cansancio ni dolor",
            "ningún dolor", "no me dolió nada", "cero molestias", "no estuvo mal", "nunca tuve calor"
        ).forEach {
            assertFalse("flagged: $it", EffortCalculator.evaluateTrenchDay(it, 300.0).isTrenchDay)
        }
        assertTrue(EffortCalculator.evaluateTrenchDay("sin dolor pero con mucho calor", 300.0).isTrenchDay)
        assertTrue(EffortCalculator.evaluateTrenchDay("Sin ganas. Hacía calor", 300.0).isTrenchDay)
    }

    @Test
    fun caloriesAreNotHeat() {
        assertFalse(EffortCalculator.evaluateTrenchDay("quemé 700 calorías", 300.0).isTrenchDay)
        assertTrue(EffortCalculator.findSufferingKeywords("quemé 700 calorías, qué calor").contains("calor"))
    }

    @Test
    fun accentsAndCaseAreIgnored() {
        assertEquals(listOf("cansancio"), EffortCalculator.findSufferingKeywords("CANSANCIO"))
        assertEquals(listOf("dolio"), EffortCalculator.findSufferingKeywords("Me DOLIÓ"))
        assertEquals(listOf("frio"), EffortCalculator.findSufferingKeywords("Frío"))
    }

    @Test
    fun paceNeverDecidesTheTrenchDay() {
        val paces = listOf(null, 240, 450, 600)
        val results = paces.map { EffortCalculator.evaluateTrenchDay("pesado", 150.0, it) }
        assertTrue(results.all { it == results.first() })
        val easy = paces.map { EffortCalculator.evaluateTrenchDay("todo bien", 150.0, it) }
        assertTrue(easy.none { it.isTrenchDay })
    }

    @Test
    fun sessionPointsApplyTheTrenchMultiplier() {
        val plain = EffortCalculator.evaluateSession(60.0, 150, 170, 35, "buen día", 330)
        val trench = EffortCalculator.evaluateSession(60.0, 150, 170, 35, "pesado y con lluvia", 330)
        assertFalse(plain.isTrenchDay)
        assertTrue(trench.isTrenchDay)
        assertEquals(1.2, trench.multiplier, 1e-9)
        assertEquals(Math.round(score() * 1.2).toInt(), trench.points)
        assertEquals(Math.round(score()).toInt(), plain.points)
        assertFalse(plain.estimated)
    }

    @Test
    fun sessionWithoutHeartRateIsEstimatedAndNeverTrench() {
        val result = EffortCalculator.evaluateSession(90.0, null, null, null, "dolor, calor, pesado")
        assertTrue(result.estimated)
        assertEquals(EffortBasis.NO_HEART_RATE, result.basis)
        assertFalse(result.isTrenchDay)
        assertEquals(180, result.points)
        val noDuration = EffortCalculator.evaluateSession(null, 165, 180, 30, "dolor")
        assertTrue(noDuration.estimated)
        assertEquals(EffortBasis.NO_DURATION, noDuration.basis)
        assertFalse(noDuration.isTrenchDay)
        val nothing = EffortCalculator.evaluateSession(null, null, null, null, "dolor")
        assertEquals(EffortBasis.NO_DATA, nothing.basis)
        assertEquals(EffortBasis.MEASURED, EffortCalculator.evaluateSession(60.0, 150, 170, 35, null).basis)
    }

    @Test
    fun sessionPointsDoNotDependOnPace() {
        val slow = EffortCalculator.evaluateSession(50.0, 155, 172, 40, "pesado", 450)
        val fast = EffortCalculator.evaluateSession(50.0, 155, 172, 40, "pesado", 240)
        assertEquals(slow, fast)
    }

    @Test
    fun aBeginnerAndAVeteranSufferingAlikeEarnTheSame() {
        // Same relative effort, wildly different pace (7:30 vs 4:00 min/km).
        val beginner = EffortCalculator.evaluateSession(40.0, 165, 178, 35, "pesado", 450)
        val veteran = EffortCalculator.evaluateSession(40.0, 165, 178, 35, "pesado", 240)
        assertEquals(beginner.points, veteran.points)
        assertTrue(beginner.isTrenchDay && veteran.isTrenchDay)
    }
}
