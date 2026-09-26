package com.example.isight.ar

import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlin.math.atan2

/**
 * Snapshot of the ARCore camera pose for one frame, in ARCore's session-local
 * world coordinates (right-handed, Y-up, units in meters, per the ARCore
 * [Pose] contract). These coordinates are NOT stable across app restarts or
 * new sessions — that gap is what the mapping/relocalization phases exist to
 * close. Treat this as "where am I relative to where this session started."
 */
data class PoseInfo(
    val trackingState: TrackingState,
    val trackingFailureReason: TrackingFailureReason,
    val x: Float,
    val y: Float,
    val z: Float,
    val headingDegrees: Float
) {
    companion object {
        fun fromCamera(
            pose: Pose,
            trackingState: TrackingState,
            trackingFailureReason: TrackingFailureReason
        ): PoseInfo {
            val qx = pose.qx()
            val qy = pose.qy()
            val qz = pose.qz()
            val qw = pose.qw()

            // Heading (rotation about the world "up"/Y axis) extracted from
            // the rotation quaternion. This is a debug-display convenience,
            // not a fully general Euler decomposition — good enough for an
            // on-screen readout; revisit if it ever has to feed navigation.
            val yawRadians = atan2(
                2.0 * (qw * qy + qx * qz),
                1.0 - 2.0 * (qy * qy + qz * qz)
            )

            return PoseInfo(
                trackingState = trackingState,
                trackingFailureReason = trackingFailureReason,
                x = pose.tx(),
                y = pose.ty(),
                z = pose.tz(),
                headingDegrees = Math.toDegrees(yawRadians).toFloat()
            )
        }
    }
}
