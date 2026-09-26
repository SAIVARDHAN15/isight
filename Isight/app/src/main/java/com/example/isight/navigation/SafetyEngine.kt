package com.example.isight.navigation

import com.example.isight.vision.DistanceZone
import com.example.isight.vision.ObjectTrack

enum class SafetyTier { WHITE, BLUE, RED }

data class SafetyAlert(val tier: SafetyTier, val message: String, val relatedTrackId: Int?)

/**
 * Deterministic, rule-based moment-to-moment safety classification from live
 * [ObjectTrack]s. This NEVER consults the command parser or anything
 * LLM-ish — per this project's explicit rule, safety decisions must stay
 * fully deterministic and auditable, never delegated to a language model.
 *
 * Temporal stabilization: a track must read [DistanceZone.VERY_NEAR] for
 * [CONSECUTIVE_FRAMES_FOR_RED] consecutive evaluations before it escalates
 * to RED, so a single noisy/wrong frame can't trigger a false "stop" alert.
 */
class SafetyEngine {

    private companion object {
        const val CONSECUTIVE_FRAMES_FOR_RED = 3
    }

    private val consecutiveNearCounts = mutableMapOf<Int, Int>()

    fun evaluate(tracks: List<ObjectTrack>): SafetyAlert {
        consecutiveNearCounts.keys.retainAll(tracks.map { it.trackId }.toSet())

        var worst = SafetyAlert(SafetyTier.WHITE, "Clear.", null)

        for (track in tracks) {
            val isVeryNear = track.distanceZone == DistanceZone.VERY_NEAR
            val consecutiveCount = if (isVeryNear) (consecutiveNearCounts[track.trackId] ?: 0) + 1 else 0
            consecutiveNearCounts[track.trackId] = consecutiveCount

            val candidate = when {
                isVeryNear && consecutiveCount >= CONSECUTIVE_FRAMES_FOR_RED -> SafetyAlert(
                    SafetyTier.RED,
                    "Stop. ${track.label} very close, ${directionWord(track)}.",
                    track.trackId
                )
                track.distanceZone == DistanceZone.NEAR ||
                    (track.approaching && track.distanceZone == DistanceZone.MEDIUM) -> SafetyAlert(
                    SafetyTier.BLUE,
                    "${track.label} ${directionWord(track)}, use caution.",
                    track.trackId
                )
                else -> SafetyAlert(SafetyTier.WHITE, "${track.label} detected ahead.", track.trackId)
            }

            if (candidate.tier.ordinal > worst.tier.ordinal) {
                worst = candidate
            }
        }

        return worst
    }

    fun reset() {
        consecutiveNearCounts.clear()
    }

    private fun directionWord(track: ObjectTrack): String = track.direction.name.lowercase()
}
