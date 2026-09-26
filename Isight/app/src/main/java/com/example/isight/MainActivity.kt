package com.example.isight

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.isight.ar.ARCoreRenderer
import com.example.isight.ar.ARCoreSessionManager
import com.example.isight.ar.ArSessionResult
import com.example.isight.ar.DisplayRotationHelper
import com.example.isight.ar.PoseInfo
import com.google.ar.core.TrackingState

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var poseText: TextView
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var arSessionManager: ARCoreSessionManager
    private lateinit var displayRotationHelper: DisplayRotationHelper
    private lateinit var arRenderer: ARCoreRenderer

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                statusText.text = getString(R.string.camera_permission_denied)
            }
            // If granted, onResume() runs again automatically right after this
            // permission dialog is dismissed, and starts ARCore from there.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        poseText = findViewById(R.id.poseText)
        glSurfaceView = findViewById(R.id.glSurfaceView)

        arSessionManager = ARCoreSessionManager(this)
        displayRotationHelper = DisplayRotationHelper(this)
        arRenderer = ARCoreRenderer(displayRotationHelper)
        arRenderer.onTelemetryUpdate = { poseInfo, centerDepthMeters ->
            runOnUiThread { showTelemetry(poseInfo, centerDepthMeters) }
        }

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(arRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun showTelemetry(poseInfo: PoseInfo, centerDepthMeters: Float?) {
        if (poseInfo.trackingState != TrackingState.TRACKING) {
            poseText.text = getString(
                R.string.pose_not_tracking,
                poseInfo.trackingState.name,
                poseInfo.trackingFailureReason.name
            )
            return
        }

        val distanceText = when {
            !arRenderer.isDepthSupported -> getString(R.string.distance_unsupported)
            centerDepthMeters == null -> getString(R.string.distance_unavailable)
            else -> getString(R.string.distance_meters, centerDepthMeters)
        }

        poseText.text = getString(
            R.string.pose_tracking,
            poseInfo.trackingState.name,
            poseInfo.x,
            poseInfo.y,
            poseInfo.z,
            poseInfo.headingDegrees,
            distanceText
        )
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            statusText.text = getString(R.string.requesting_camera_permission)
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        when (val result = arSessionManager.tryCreateSession()) {
            is ArSessionResult.Ready -> {
                arRenderer.session = result.session
                arRenderer.isDepthSupported = arSessionManager.isDepthSupported
                displayRotationHelper.onResume()
                glSurfaceView.onResume()
                statusText.text = getString(R.string.arcore_ready)
            }
            is ArSessionResult.InstallRequested -> {
                statusText.text = getString(R.string.installing_arcore)
            }
            is ArSessionResult.Unavailable -> {
                statusText.text = getString(R.string.arcore_unavailable_prefix, result.reason)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Only tear down the GL/render side if we actually stood it up in
        // onResume (i.e. a session exists). Order matters: stop feeding the
        // renderer new frames (rotation helper, then the GL thread itself)
        // *before* pausing the underlying ARCore session.
        if (arSessionManager.session != null) {
            displayRotationHelper.onPause()
            glSurfaceView.onPause()
            arRenderer.session = null
            arSessionManager.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        arSessionManager.close()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
