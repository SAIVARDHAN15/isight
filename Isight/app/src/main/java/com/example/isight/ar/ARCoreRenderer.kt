package com.example.isight.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "ARCoreRenderer"

// Debug-overlay readout cadence. Deliberately not every frame: pushing a
// TextView update to the UI thread 60x/sec buys nothing for a human reader
// and just adds needless work on the GL thread's critical path.
private const val TELEMETRY_UPDATE_INTERVAL_NANOS = 150_000_000L // ~6-7 Hz

/**
 * GLSurfaceView.Renderer that draws the ARCore camera feed as a background
 * and reports camera pose + center-of-frame depth updates.
 *
 * [session] is set/cleared by the activity as the ARCore session comes and
 * goes across the Android lifecycle; it is read from the GL thread every
 * frame, so it is [Volatile] for cross-thread visibility. Same for
 * [isDepthSupported].
 */
class ARCoreRenderer(
    private val displayRotationHelper: DisplayRotationHelper
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    var isDepthSupported: Boolean = false

    /** Invoked from the GL thread — hop to the UI thread yourself before touching views. */
    var onTelemetryUpdate: ((PoseInfo, Float?) -> Unit)? = null

    private val backgroundRenderer = BackgroundRenderer()
    private var cameraTextureBound = false
    private var lastTelemetryUpdateNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        cameraTextureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        // Must happen before the first Session#update() call.
        if (!cameraTextureBound) {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            cameraTextureBound = true
        }

        displayRotationHelper.updateSessionIfNeeded(currentSession)

        val frame = try {
            currentSession.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            return
        }

        backgroundRenderer.draw(frame)
        maybeReportTelemetry(frame)
    }

    private fun maybeReportTelemetry(frame: Frame) {
        val callback = onTelemetryUpdate ?: return

        val now = System.nanoTime()
        if (now - lastTelemetryUpdateNanos < TELEMETRY_UPDATE_INTERVAL_NANOS) return
        lastTelemetryUpdateNanos = now

        val camera = frame.camera
        val poseInfo = PoseInfo.fromCamera(
            pose = camera.pose,
            trackingState = camera.trackingState,
            trackingFailureReason = camera.trackingFailureReason
        )
        val centerDepthMeters = if (isDepthSupported) {
            DepthProcessor.centerDepthMeters(frame)
        } else {
            null
        }

        callback(poseInfo, centerDepthMeters)
    }
}
