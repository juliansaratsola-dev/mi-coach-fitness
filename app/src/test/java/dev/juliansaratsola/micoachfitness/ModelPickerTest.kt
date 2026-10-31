package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPickerTest {
    @Test
    fun picksNewestStableFlash() {
        val names = listOf(
            "models/gemini-2.5-flash",
            "models/gemini-3.8-flash",
            "models/gemini-3.10-flash",
            "models/gemini-3.9-flash-lite",
            "models/gemini-3.8-flash-tts",
            "models/gemini-4.0-pro"
        )
        assertEquals("gemini-3.10-flash", ModelPicker.pickFlash(names))
    }

    @Test
    fun ranksFlashBeforeLiteAsFallback() {
        val names = listOf("models/gemini-3.9-flash-lite", "models/gemini-3.8-flash", "models/gemini-2.5-flash")
        assertEquals(
            listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-3.9-flash-lite"),
            ModelPicker.rankFlash(names)
        )
    }

    @Test
    fun returnsNullWithoutFlash() {
        assertNull(ModelPicker.pickFlash(listOf("models/gemini-3.8-pro", "models/embedding-001")))
    }
}
