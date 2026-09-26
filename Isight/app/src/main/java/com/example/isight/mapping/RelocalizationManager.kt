package com.example.isight.mapping

import kotlin.math.cos
import kotlin.math.sin

/** A raw ARCore camera pose, already extracted to plain floats (session-local coordinates, degrees heading). */
data class SessionPose(val x: Float, val y: Float, val z: Float, val headingDegrees: Float)

/**
 * Aligns a NEW ARCore session's coordinate frame with a persistent
 * [SemanticMap]'s coordinate frame, so "take me to the kitchen" can work
 * across app restarts even though ARCore itself starts every session at a
 * fresh, unrelated origin.
 *
 * ## Limitation — read before trusting this
 * This project does **not** implement automatic visual relocalization
 * (recognizing "I'm in the same room as yesterday" purely from camera
 * imagery / feature matching against saved landmarks). That is a genuinely
 * hard computer-vision problem (loop closure / place recognition), and
 * ARCore itself only offers cross-session alignment via **Cloud Anchors**
 * (requires network + a Google Cloud project) or the **Geospatial API**
 * (requires VPS coverage, essentially outdoor-only). Neither fits "fully
 * on-device, works indoors, no cloud dependency," which is what this
 * project needs.
 *
 * ## Practical approximation implemented instead: user-assisted calibration
 * The user is asked to stand at one known reference point — by convention,
 * whatever landmark/room the map calls "entrance" — and confirm it (e.g. by
 * saying "I'm at the entrance"). At that single moment, [calibrate] computes
 * one rigid 2D transform (yaw rotation about the vertical axis + XZ
 * translation) from the CURRENT session's ARCore pose to the STORED
 * entrance position/heading in the persistent map. Every later pose in this
 * session is passed through that same fixed transform via
 * [toMapCoordinates].
 *
 * This is exact at the calibration point and degrades gracefully as ARCore
 * accumulates its own ordinary drift away from it (typically fine at
 * home/room scale over a single walking session). It is explicitly **not**
 * a substitute for true visual relocalization — if the user starts the app
 * without recalibrating, [toMapCoordinates] simply returns null until they
 * do, rather than silently returning a wrong position.
 */
class RelocalizationManager {

    private data class RigidTransform2D(val translationX: Float, val translationZ: Float, val rotationDegrees: Float)

    private var transform: RigidTransform2D? = null

    val isAligned: Boolean get() = transform != null

    fun calibrate(currentSessionPose: SessionPose, storedPosition: Vec3, storedHeadingDegrees: Float) {
        transform = RigidTransform2D(
            translationX = storedPosition.x - currentSessionPose.x,
            translationZ = storedPosition.z - currentSessionPose.z,
            rotationDegrees = storedHeadingDegrees - currentSessionPose.headingDegrees
        )
    }

    fun reset() {
        transform = null
    }

    /** Maps a raw ARCore-session pose into persistent-map coordinates, or null if not yet calibrated this session. */
    fun toMapCoordinates(sessionPose: SessionPose): Vec3? {
        val t = transform ?: return null
        val (rotatedX, rotatedZ) = rotateAboutY(sessionPose.x, sessionPose.z, t.rotationDegrees)
        return Vec3(rotatedX + t.translationX, sessionPose.y, rotatedZ + t.translationZ)
    }

    private fun rotateAboutY(x: Float, z: Float, degrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(degrees.toDouble())
        val cosR = cos(radians).toFloat()
        val sinR = sin(radians).toFloat()
        return (x * cosR - z * sinR) to (x * sinR + z * cosR)
    }
}
