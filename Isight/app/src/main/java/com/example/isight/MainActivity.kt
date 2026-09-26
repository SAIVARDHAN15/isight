package com.example.isight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.isight.ar.ARCoreSessionManager
import com.example.isight.ar.ArSessionResult

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var arSessionManager: ARCoreSessionManager

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
        arSessionManager = ARCoreSessionManager(this)
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
        arSessionManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSessionManager.close()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
