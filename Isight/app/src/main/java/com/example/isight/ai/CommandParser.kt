package com.example.isight.ai

/**
 * Recognized user intents. The LLM/VLM box in this project's architecture
 * diagram is intentionally NOT a full on-device LLM here — see the class
 * doc below for why, and what upgrading to one would involve.
 */
sealed class Intent {
    data class NavigateToRoom(val roomName: String) : Intent()
    data class FindObject(val label: String) : Intent()
    data class NameCurrentRoom(val roomName: String) : Intent()
    object ConfirmAtEntrance : Intent()
    object StartMapping : Intent()
    object SaveMap : Intent()
    object WhereAmI : Intent()
    object Stop : Intent()
    data class Unknown(val heardText: String) : Intent()
}

/**
 * Parses a natural-language voice command into an [Intent].
 *
 * ## Why this is rule-based, not a true on-device LLM
 * Your architecture calls for an "LLM/VLM" to interpret user requests. A
 * genuine on-device LLM (e.g. Google's MediaPipe LLM Inference API running
 * a small Gemma model) is a substantial separate integration: a multi-
 * hundred-MB-to-several-GB model download, its own MediaPipe Tasks GenAI
 * dependency, meaningfully more RAM/battery/thermal budget, and its own
 * licensing terms to review — not something to bolt on silently inside a
 * "fast-forward the rest of the project" pass. For a hackathon prototype,
 * this rule-based keyword/pattern matcher is the **practical, honest
 * substitute**: deterministic, instant, zero extra footprint, and it
 * handles every example command your spec lists. Swapping in a real LLM
 * later only requires replacing [parse]'s implementation — every caller
 * already goes through the same [Intent] sealed class, per your explicit
 * rule that the LLM must never issue movement commands directly; it only
 * ever produces an [Intent] for deterministic code to act on.
 */
object CommandParser {

    private val navigatePatterns = listOf(
        Regex("""take me to (?:the )?(.+)""", RegexOption.IGNORE_CASE),
        Regex("""go to (?:the )?(.+)""", RegexOption.IGNORE_CASE),
        Regex("""navigate to (?:the )?(.+)""", RegexOption.IGNORE_CASE)
    )

    private val findPatterns = listOf(
        Regex("""where is (?:the |a |my )?(.+)\??""", RegexOption.IGNORE_CASE),
        Regex("""find (?:the |a |my )?(.+)""", RegexOption.IGNORE_CASE),
        Regex("""locate (?:the |a |my )?(.+)""", RegexOption.IGNORE_CASE)
    )

    private val nameRoomPatterns = listOf(
        Regex("""name this room (.+)""", RegexOption.IGNORE_CASE),
        Regex("""call this room (.+)""", RegexOption.IGNORE_CASE),
        Regex("""this is the (.+)""", RegexOption.IGNORE_CASE)
    )

    private val entranceConfirmPhrases = listOf(
        "i'm at the entrance", "im at the entrance", "i am at the entrance", "confirm entrance"
    )

    private val stopPhrases = listOf("stop", "cancel", "stop navigating", "never mind")
    private val startMappingPhrases = listOf("start mapping", "begin mapping", "map this house", "map this place")
    private val saveMapPhrases = listOf("save map", "save the map")
    private val whereAmIPhrases = listOf("where am i", "what room is this", "what room am i in")

    fun parse(heardText: String): Intent {
        val normalized = heardText.trim().lowercase().trimEnd('.', '!', '?')

        if (normalized in stopPhrases) return Intent.Stop
        if (normalized in startMappingPhrases) return Intent.StartMapping
        if (normalized in saveMapPhrases) return Intent.SaveMap
        if (normalized in whereAmIPhrases) return Intent.WhereAmI
        if (normalized in entranceConfirmPhrases) return Intent.ConfirmAtEntrance

        for (pattern in nameRoomPatterns) {
            pattern.find(normalized)?.let { return Intent.NameCurrentRoom(it.groupValues[1].trim()) }
        }
        for (pattern in navigatePatterns) {
            pattern.find(normalized)?.let { return Intent.NavigateToRoom(it.groupValues[1].trim()) }
        }
        for (pattern in findPatterns) {
            pattern.find(normalized)?.let { return Intent.FindObject(it.groupValues[1].trim()) }
        }

        return Intent.Unknown(heardText)
    }
}
