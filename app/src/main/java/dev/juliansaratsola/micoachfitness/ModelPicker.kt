package dev.juliansaratsola.micoachfitness

/** Picks the newest stable Flash model from the names returned by the Gemini ListModels call. */
object ModelPicker {
    private val FLASH = Regex("^gemini-(\\d+)(?:\\.(\\d+))?-flash$")

    fun pickFlash(names: List<String>): String? {
        return names
            .map { it.removePrefix("models/") }
            .mapNotNull { name ->
                FLASH.matchEntire(name)?.let { match ->
                    val major = match.groupValues[1].toInt()
                    val minor = match.groupValues[2].toIntOrNull() ?: 0
                    Triple(major, minor, name)
                }
            }
            .maxWithOrNull(compareBy({ it.first }, { it.second }))
            ?.third
    }
}
