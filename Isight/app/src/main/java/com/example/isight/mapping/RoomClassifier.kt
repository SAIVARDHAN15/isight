package com.example.isight.mapping

/**
 * Suggests a likely room TYPE from the COCO object labels seen in it — a
 * SUGGESTION only. Per this project's explicit safety rule, an uncertain
 * room classification must never silently become the room's name; the
 * caller (voice/command layer) should offer this as a spoken suggestion
 * ("this looks like it might be a kitchen — say 'name this room kitchen'
 * to confirm") and only commit a name on explicit user confirmation.
 */
object RoomClassifier {

    private val signalsByRoomType = mapOf(
        "kitchen" to setOf("refrigerator", "oven", "microwave", "toaster", "sink"),
        "living room" to setOf("couch", "tv", "remote"),
        "bedroom" to setOf("bed"),
        "bathroom" to setOf("toilet", "sink"),
        "dining room" to setOf("dining table", "chair", "wine glass")
    )

    /** Require at least this many distinct corroborating object labels before suggesting anything. */
    private const val MIN_CORROBORATING_SIGNALS = 2

    fun suggestRoomType(objectLabels: List<String>): String? {
        val labelSet = objectLabels.map { it.lowercase() }.toSet()

        val scored = signalsByRoomType.mapValues { (_, signals) -> signals.count { it in labelSet } }
        val best = scored.maxByOrNull { it.value } ?: return null

        return if (best.value >= MIN_CORROBORATING_SIGNALS) best.key else null
    }
}
