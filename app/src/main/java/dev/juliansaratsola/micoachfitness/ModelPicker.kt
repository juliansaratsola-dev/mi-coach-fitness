package dev.juliansaratsola.micoachfitness

/** Ranks Flash models from the names returned by the Gemini ListModels call. */
object ModelPicker {
    private val FLASH = Regex("^gemini-(\\d+)(?:\\.(\\d+))?-flash(-lite)?$")

    /** Newest stable Flash first, then Flash-Lite as a lighter fallback when the main models are overloaded. */
    fun rankFlash(names: List<String>): List<String> {
        val parsed = names
            .map { it.removePrefix("models/") }
            .distinct()
            .mapNotNull { name ->
                FLASH.matchEntire(name)?.let { match ->
                    Candidate(
                        name,
                        match.groupValues[3].isNotEmpty(),
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toIntOrNull() ?: 0
                    )
                }
            }
        return parsed
            .sortedWith(compareBy<Candidate>({ it.lite }, { -it.major }, { -it.minor }))
            .map { it.name }
    }

    /** Newest Flash, then a Flash from an older generation, then Flash-Lite: overload usually hits the newest models together. */
    fun fallbackOrder(names: List<String>): List<String> {
        val ranked = rankFlash(names)
        val flash = ranked.filter { !it.endsWith("-lite") }
        val lite = ranked.filter { it.endsWith("-lite") }
        val major = { name: String -> name.removePrefix("gemini-").substringBefore('.').substringBefore('-').toInt() }
        val older = flash.drop(1).firstOrNull { major(it) < major(flash.first()) } ?: flash.drop(1).lastOrNull()
        return listOfNotNull(flash.firstOrNull(), older, lite.firstOrNull())
            .plus(ranked)
            .distinct()
    }

    fun pickFlash(names: List<String>): String? = rankFlash(names).firstOrNull()

    private data class Candidate(val name: String, val lite: Boolean, val major: Int, val minor: Int)
}
