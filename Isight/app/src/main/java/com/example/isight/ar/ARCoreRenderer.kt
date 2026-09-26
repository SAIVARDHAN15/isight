package com.example.isight.ar

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.isight.vision.DetectionPipeline
import com.example.isight.vision.DetectionResult
import com.example.isight.vision.ObjectFusionEngine
import com.example.isight.vision.ObjectTrack
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "ARCoreRenderer"

// Debug-overlay / fusion readout cadence. Deliberately not every frame:
// pushing UI updates 60x/sec buys nothing for a human reader and just adds
// needless work on the GL thread's critical path.
private const val TELEMETRY_UPDATE_INTERVAL_NANOS = 150_000_000L // ~6-7 Hz

/**
 * GLSurfaceView.Renderer that draws the ARCore camera feed as a background,
 * reports camera pose + center-of-frame depth for the debug overlay, and
 * drives the vision pipeline.
 *
 * THREADING (Phase 12): heavy work — YUV preprocessing and TFLite inference
 * — happens on [DetectionPipeline]'s own background executor, NOT here.
 * This GL thread only ever does two CHEAP things per frame: (1) acquire the
 * camera image and hand it to the pipeline (which drops it if busy — see
 * DetectionPipeline), and (2) sample depth for whatever detections the
 * pipeline most recently produced, since depth data is only valid against
 * the CURRENT frame and can't be deferred to a worker thread the way
 * preprocessing/inference can (see [DepthProcessor.boxDepthMeters]'s
 * staleness note).
 *
 * [session] and [isDepthSupported] are set/cleared by the activity as the
 * ARCore session comes and goes across the Android lifecycle; read from the
 * GL thread every frame, so both are [Volatile] for cross-thread visibility.
 */
class ARCoreRenderer(
    private val displayRotationHelper: DisplayRotationHelper
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    var isDepthSupported: Boolean = false

    @Volatile
    var detectionPipeline: DetectionPipeline? = null

    /** All callbacks below are invoked from the GL thread — hop to the UI thread yourself. */
    var onTelemetryUpdate: ((PoseInfo, Float?) -> Unit)? = null
    var onPreprocessedPreview: ((Bitmap) -> Unit)? = null
    var onObjectTracks: ((List<ObjectTrack>, imageWidth: Int, imageHeight: Int) -> Unit)? = null

    private val backgroundRenderer = BackgroundRenderer()
    private val fusionEngine = ObjectFusionEngine()

    private var cameraTextureBound = false
    private var lastTelemetryUpdateNanos = 0L

    @Volatile
    private var latestDetectionResult: DetectionResult? = null

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
        maybeSubmitFrameToPipeline(frame)
        maybeUpdateTelemetryAndTracks(frame)
    }

    private fun maybeSubmitFrameToPipeline(frame: Frame) {
        val pipeline = detectionPipeline ?: return
        try {
            val image = frame.acquireCameraImage()
            pipeline.submitFrame(image) // takes ownership; always closes it
        } catch (e: NotYetAvailableException) {
            // Normal for the first several frames after session start.
        } catch (e: DeadlineExceededException) {
            Log.w(TAG, "acquireCameraImage: frame is no longer the current one")
        } catch (e: ResourceExhaustedException) {
            Log.w(TAG, "acquireCameraImage: too many outstanding camera images")
        }
    }

    private fun maybeUpdateTelemetryAndTracks(frame: Frame) {
        val now = System.nanoTime()
        if (now - lastTelemetryUpdateNanos < TELEMETRY_UPDATE_INTERVAL_NANOS) return
        lastTelemetryUpdateNanos = now

        reportPoseAndDepthTelemetry(frame)
        reportObjectTracks(frame)
    }

    private fun reportPoseAndDepthTelemetry(frame: Frame) {
        val callback = onTelemetryUpdate ?: return

        val camera = frame.camera
        val poseInfo = PoseInfo.fromCamera(
            pose = camera.pose,
            trackingState = camera.trackingState,
            trackingFailureReason = camera.trackingFailureReason
        )
        val centerDepthMeters = if (isDepthSupported) DepthProcessor.centerDepthMeters(frame) else null
        callback(poseInfo, centerDepthMeters)
    }

    private fun reportObjectTracks(frame: Frame) {
        val result = latestDetectionResult ?: return

        onPreprocessedPreview?.invoke(result.debugBitmap)

        val tracksCallback = onObjectTracks ?: return
        val tracks = fusionEngine.update(
            detections = result.detections,
            imageWidth = result.preprocessResult.imageWidth
        ) { detection ->
            if (isDepthSupported) DepthProcessor.boxDepthMeters(frame, detection, result.preprocessResult) else null
        }
        tracksCallback(tracks, result.preprocessResult.imageWidth, result.preprocessResult.imageHeight)
    }

    /** Wire this to the pipeline's onResult from the activity, once the model is loaded. */
    fun onDetectionResult(result: DetectionResult) {
        latestDetectionResult = result
    }

    fun resetFusion() {
        fusionEngine.reset()
    }
}
