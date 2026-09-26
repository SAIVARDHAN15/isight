package com.example.isight.vision

enum class Direction { LEFT, CENTER, RIGHT }

enum class DistanceZone { VERY_NEAR, NEAR, MEDIUM, FAR, UNKNOWN }

/**
 * Final fused per-object output: identity + geometry + distance + direction
 * + simple approach velocity. This is what the safety engine and navigation
 * layers consume — they should never need to look at raw [Detection]s.
 */
data class ObjectTrack(
    val trackId: Int,
    val classId: Int,
    val label: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val boundingBox: Detection,
    val distanceMeters: Float?,
    val distanceZone: DistanceZone,
    val direction: Direction,
    val velocityMetersPerSecond: Float?,
    val approaching: Boolean
)

/** Configurable distance-zone thresholds (meters). */
object DistanceZones {
    const val VERY_NEAR_MAX_METERS = 0.75f
    const val NEAR_MAX_METERS = 1.5f
    const val MEDIUM_MAX_METERS = 3.0f

    fun classify(distanceMeters: Float?): DistanceZone = when {
        distanceMeters == null -> DistanceZone.UNKNOWN
        distanceMeters <= VERY_NEAR_MAX_METERS -> DistanceZone.VERY_NEAR
        distanceMeters <= NEAR_MAX_METERS -> DistanceZone.NEAR
        distanceMeters <= MEDIUM_MAX_METERS -> DistanceZone.MEDIUM
        else -> DistanceZone.FAR
    }
}

/** Configurable direction thresholds, as a fraction of image width. */
object DirectionZones {
    /** Width of the "CENTER" band, centered on the image's horizontal midpoint. */
    const val CENTER_BAND_FRACTION = 0.34f

    fun classify(centerX: Float, imageWidth: Int): Direction {
        if (imageWidth <= 0) return Direction.CENTER
        val fraction = centerX / imageWidth
        val halfBand = CENTER_BAND_FRACTION / 2f
        return when {
            fraction < 0.5f - halfBand -> Direction.LEFT
            fraction > 0.5f + halfBand -> Direction.RIGHT
            else -> Direction.CENTER
        }
    }
}
