package com.example.isight.vision

import kotlin.math.max
import kotlin.math.min

/** A [Detection] with a stable identity across frames. */
data class TrackedDetection(val trackId: Int, val detection: Detection)

/**
 * Lightweight IoU-based multi-object tracker: matches each new frame's
 * detections against existing tracks (same class + highest IoU above
 * threshold wins), keeping a track alive for a few missed frames before
 * dropping it, so a track surviving one bad detection frame doesn't get a
 * new ID. This replaces the placeholder tracker that assigned a fresh ID to
 * every detection every frame.
 *
 * Not thread-confined by itself — callers must serialize access (this
 * project always calls [update] from a single thread at a time).
 */
class ObjectTracker(
    private val iouThreshold: Float = 0.3f,
    private val maxMissedFrames: Int = 8
) {
    private class Track(var detection: Detection, val trackId: Int) {
        var missedFrames = 0
    }

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1

    fun update(detections: List<Detection>): List<TrackedDetection> {
        val unmatched = detections.toMutableList()

        for (track in tracks) {
            val match = unmatched
                .filter { it.classId == track.detection.classId }
                .maxByOrNull { iou(track.detection, it) }
                ?.takeIf { iou(track.detection, it) >= iouThreshold }

            if (match != null) {
                track.detection = match
                track.missedFrames = 0
                unmatched.remove(match)
            } else {
                track.missedFrames++
            }
        }

        tracks.removeAll { it.missedFrames > maxMissedFrames }

        for (detection in unmatched) {
            tracks.add(Track(detection, nextTrackId++))
        }

        return tracks
            .filter { it.missedFrames == 0 }
            .map { TrackedDetection(it.trackId, it.detection) }
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }

    private fun iou(a: Detection, b: Detection): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
