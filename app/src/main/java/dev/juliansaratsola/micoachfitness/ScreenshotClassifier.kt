package dev.juliansaratsola.micoachfitness

import java.util.Locale

/**
 * Screenshot heuristic based on MediaStore name and path.
 * Markers required by the MVP: Screenshots, screenshot, captura.
 */
object ScreenshotClassifier {
    private val markers = listOf("screenshots", "screenshot", "captura")

    fun isScreenshot(displayName: String?, relativePath: String?, dataPath: String?): Boolean {
        val haystack = listOfNotNull(displayName, relativePath, dataPath)
            .joinToString(separator = " ")
            .lowercase(Locale.ROOT)
        if (haystack.isBlank()) return false
        return markers.any { marker -> haystack.contains(marker) }
    }
}
