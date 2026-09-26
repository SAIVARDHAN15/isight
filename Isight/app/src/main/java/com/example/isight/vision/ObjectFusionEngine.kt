package com.example.isight.vision

private const val APPROACH_VELOCITY_THRESHOLD_MPS = 0.05f

/**
 * Combines object identity (via [ObjectTracker]) with depth samples into
 * final [ObjectTrack]s: direction, distance zone, and simple approach
 * velocity from consecutive per-track distance readings.
 *
 * Deliberately has NO dependency on ARCore types. Depth for each detection
 * is supplied by the caller via [depthLookup] — this project's caller
 * (ARCoreRenderer) supplies it from com.example.isight.ar.DepthProcessor
 * against the current frame. Keeping that dependency out of this class
 * avoids a circular ar<->vision package dependency and keeps fusion logic
 * pure/testable.
 */
class ObjectFusionEngine {

    private val tracker = ObjectTracker()

    private class DistanceReading(var meters: Float, var atNanos: Long)
    private val distanceHistory = mutableMapOf<Int, DistanceReading>()

    fun update(
        detections: List<Detection>,
        imageWidth: Int,
        depthLookup: (Detection) -> Float?
    ): List<ObjectTrack> {
        val tracked = tracker.update(detections)
        val now = System.nanoTime()

        distanceHistory.keys.retainAll(tracked.map { it.trackId }.toSet())

        return tracked.map { (trackId, detection) ->
            val distance = depthLookup(detection)
            val previous = distanceHistory[trackId]

            val velocity = if (distance != null && previous != null) {
                val dtSeconds = (now - previous.atNanos) / 1_000_000_000f
                if (dtSeconds > 0.05f) (distance - previous.meters) / dtSeconds else null
            } else {
                null
            }

            if (distance != null) {
                distanceHistory[trackId] = DistanceReading(distance, now)
            }

            ObjectTrack(
                trackId = trackId,
                classId = detection.classId,
                label = detection.label,
                confidence = detection.confidence,
                centerX = detection.centerX,
                centerY = detection.centerY,
                boundingBox = detection,
                distanceMeters = distance,
                distanceZone = DistanceZones.classify(distance),
                direction = DirectionZones.classify(detection.centerX, imageWidth),
                velocityMetersPerSecond = velocity,
                approaching = (velocity ?: 0f) < -APPROACH_VELOCITY_THRESHOLD_MPS
            )
        }
    }

    fun reset() {
        tracker.reset()
        distanceHistory.clear()
    }
}
