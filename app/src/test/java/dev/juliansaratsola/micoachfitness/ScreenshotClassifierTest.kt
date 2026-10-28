package dev.juliansaratsola.micoachfitness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotClassifierTest {
    @Test
    fun matchesScreenshotName() {
        assertTrue(ScreenshotClassifier.isScreenshot("Screenshot_20261028.png", null, null))
    }

    @Test
    fun matchesScreenshotsDirectory() {
        assertTrue(
            ScreenshotClassifier.isScreenshot(
                "IMG_0001.png",
                "Pictures/Screenshots/",
                null
            )
        )
    }

    @Test
    fun matchesCapturaInSpanishFileName() {
        assertTrue(
            ScreenshotClassifier.isScreenshot(
                "Captura de pantalla 2026-10-28.png",
                "DCIM/Camera/",
                null
            )
        )
    }

    @Test
    fun matchesDataPathWhenDisplayNameIsGeneric() {
        assertTrue(
            ScreenshotClassifier.isScreenshot(
                "image.png",
                null,
                "/storage/emulated/0/Pictures/Screenshots/image.png"
            )
        )
    }

    @Test
    fun ignoresUnrelatedPhotos() {
        assertFalse(ScreenshotClassifier.isScreenshot("vacation.jpg", "DCIM/Camera/", null))
    }

    @Test
    fun ignoresEmptyMetadata() {
        assertFalse(ScreenshotClassifier.isScreenshot(null, null, null))
        assertFalse(ScreenshotClassifier.isScreenshot("   ", "", null))
    }
}
