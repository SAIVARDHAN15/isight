package com.example.isight

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.isight.ai.CommandParser
import com.example.isight.ai.Intent
import com.example.isight.ar.ARCoreRenderer
import com.example.isight.ar.ARCoreSessionManager
import com.example.isight.ar.ArSessionResult
import com.example.isight.ar.DisplayRotationHelper
import com.example.isight.ar.PoseInfo
import com.example.isight.haptics.HapticCue
import com.example.isight.haptics.HapticFeedbackManager
import com.example.isight.mapping.MapObject
import com.example.isight.mapping.MapRepository
import com.example.isight.mapping.RelocalizationManager
import com.example.isight.mapping.Room
import com.example.isight.mapping.RoomClassifier
import com.example.isight.mapping.SemanticMap
import com.example.isight.mapping.SessionPose
import com.example.isight.mapping.Vec3
import com.example.isight.navigation.NavigationEngine
import com.example.isight.navigation.NavigationStatus
import com.example.isight.navigation.SafetyAlert
import com.example.isight.navigation.SafetyEngine
import com.example.isight.navigation.SafetyTier
import com.example.isight.trigger.VolumeButtonTrigger
import com.example.isight.ui.DetectionOverlayView
import com.example.isight.vision.DetectionPipeline
import com.example.isight.vision.ObjectTrack
import com.example.isight.vision.YoloDetector
import com.example.isight.voice.SpeechInput
import com.example.isight.voice.SpeechOutput
import com.google.ar.core.TrackingState
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "MainActivity"

// Rough back-camera horizontal FOV used only to turn "how far across the
// frame is this box" into a bearing offset for placing objects in the map.
// Not read from the device's actual camera characteristics - an estimate,
// documented as such; see estimateObjectPosition.
private const val ASSUMED_HORIZONTAL_FOV_DEGREES = 70f

private const val SAFETY_REANNOUNCE_COOLDOWN_NANOS = 4_000_000_000L // 4s
private const val NAV_REANNOUNCE_COOLDOWN_NANOS = 5_000_000_000L // 5s
private const val MAX_ROOM_ASSOCIATION_DISTANCE_METERS = 3f

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var iSightStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var tracksText: TextView
    private lateinit var modelInputPreview: ImageView
    private lateinit var navigationText: TextView
    private lateinit var poseText: TextView
    private lateinit var listenButton: Button
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var detectionOverlay: DetectionOverlayView

    // AR
    private lateinit var arSessionManager: ARCoreSessionManager
    private lateinit var displayRotationHelper: DisplayRotationHelper
    private lateinit var arRenderer: ARCoreRenderer

    // Vision (Phase 12: heavy work lives on this single background executor)
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var yoloDetector: YoloDetector? = null

    // Mapping / navigation / safety
    private lateinit var mapRepository: MapRepository
    private val relocalizationManager = RelocalizationManager()
    private val navigationEngine = NavigationEngine()
    private val safetyEngine = SafetyEngine()

    // Voice / haptics / trigger
    private lateinit var speechInput: SpeechInput
    private lateinit var speechOutput: SpeechOutput
    private lateinit var hapticFeedbackManager: HapticFeedbackManager
    private lateinit var volumeButtonTrigger: VolumeButtonTrigger
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var iSightActive = false

    @Volatile
    private var latestPoseInfo: PoseInfo? = null

    private var lastSpokenSafetyTier: SafetyTier? = null
    private var lastSafetyAnnouncementNanos = 0L
    private var lastNavigationAnnouncementNanos = 0L

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                statusText.text = getString(R.string.camera_permission_denied)
            }
            // If granted, onResume() runs again automatically right after this
            // permission dialog is dismissed, and starts ARCore from there.
        }

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListeningInternal() else speak("I need microphone permission to hear commands.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        iSightStatusText = findViewById(R.id.iSightStatusText)
        statusText = findViewById(R.id.statusText)
        modelStatusText = findViewById(R.id.modelStatusText)
        tracksText = findViewById(R.id.tracksText)
        modelInputPreview = findViewById(R.id.modelInputPreview)
        navigationText = findViewById(R.id.navigationText)
        poseText = findViewById(R.id.poseText)
        listenButton = findViewById(R.id.listenButton)
        glSurfaceView = findViewById(R.id.glSurfaceView)
        detectionOverlay = findViewById(R.id.detectionOverlay)

        iSightStatusText.text = getString(R.string.isight_off)

        arSessionManager = ARCoreSessionManager(this)
        displayRotationHelper = DisplayRotationHelper(this)
        arRenderer = ARCoreRenderer(displayRotationHelper)
        arRenderer.onTelemetryUpdate = { poseInfo, centerDepthMeters ->
            runOnUiThread { onTelemetryUpdated(poseInfo, centerDepthMeters) }
        }
        arRenderer.onPreprocessedPreview = { bitmap ->
            runOnUiThread { modelInputPreview.setImageBitmap(bitmap) }
        }
        arRenderer.onObjectTracks = { tracks, imageWidth, imageHeight ->
            runOnUiThread { onObjectTracksUpdated(tracks, imageWidth, imageHeight) }
        }

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(arRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        mapRepository = MapRepository(applicationContext)
        navigationEngine.rebuildGraph(mapRepository.currentMap)

        speechOutput = SpeechOutput(applicationContext)
        speechInput = SpeechInput(applicationContext)
        hapticFeedbackManager = HapticFeedbackManager(applicationContext)

        volumeButtonTrigger = VolumeButtonTrigger(
            context = this,
            onActivate = { runOnUiThread { activateISight() } },
            onDeactivate = { runOnUiThread { deactivateISight() } },
            onHoldProgress = { heldMillis, thresholdMillis ->
                if (heldMillis > 300L) {
                    runOnUiThread { updateHoldProgressUi(heldMillis, thresholdMillis) }
                }
            }
        )

        listenButton.setOnClickListener { startListening() }

        loadYoloModel()
    }

    // ---------------------------------------------------------------------
    // iSight ON/OFF (Phase 28/30)
    //
    // SCOPING NOTE: this gates user-facing OUTPUT (voice guidance/alerts,
    // haptics) and voice-command listening, and stops recording newly
    // observed objects into the semantic map while OFF. It deliberately
    // does NOT tear down the underlying camera/ARCore/perception pipeline -
    // restarting an ARCore session on every toggle would add real lifecycle
    // risk (see ARCoreSessionManager) for no testable benefit in a
    // hackathon prototype. If a real host-app integration later needs the
    // camera fully off while inactive, that is the next incremental step
    // from here, not a silent gap.
    // ---------------------------------------------------------------------

    private fun activateISight() {
        if (iSightActive) return
        iSightActive = true
        iSightStatusText.text = getString(R.string.isight_on)
        speechOutput.speak("iSight on.", flushQueue = true)
        // Give the short confirmation utterance time to finish before
        // opening the mic, to reduce (not fully eliminate) the risk of the
        // recognizer picking up iSight's own voice.
        mainHandler.postDelayed({ startListening() }, 1200L)
    }

    private fun deactivateISight() {
        if (!iSightActive) return
        iSightActive = false
        iSightStatusText.text = getString(R.string.isight_off)
        speechInput.cancel()
        speechOutput.speak("iSight off.", flushQueue = true)
        navigationEngine.stop()
    }

    private fun updateHoldProgressUi(heldMillis: Long, thresholdMillis: Long) {
        val percent = ((heldMillis * 100) / thresholdMillis).toInt().coerceIn(0, 100)
        val action = if (!iSightActive) {
            getString(R.string.isight_holding_start)
        } else {
            getString(R.string.isight_holding_stop)
        }
        iSightStatusText.text = getString(R.string.isight_holding, action, percent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (volumeButtonTrigger.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------------
    // Voice (Phase 21/25/26)
    // ---------------------------------------------------------------------

    private fun startListening() {
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListeningInternal()
    }

    private fun startListeningInternal() {
        speak("Listening.")
        speechInput.startListening(
            onResult = { text -> runOnUiThread { handleRecognizedSpeech(text) } },
            onError = { message -> runOnUiThread { speak(message) } }
        )
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Direct response to an explicit user command - always spoken, unlike ambient safety/nav chatter. */
    private fun speak(text: String, flush: Boolean = false) {
        speechOutput.speak(text, flush)
    }

    private fun handleRecognizedSpeech(heardText: String) {
        when (val intent = CommandParser.parse(heardText)) {
            is Intent.NavigateToRoom -> onNavigateToRoom(intent.roomName)
            is Intent.FindObject -> onFindObject(intent.label)
            is Intent.NameCurrentRoom -> onNameCurrentRoom(intent.roomName)
            Intent.ConfirmAtEntrance -> onConfirmAtEntrance()
            Intent.StartMapping -> onStartMapping()
            Intent.SaveMap -> onSaveMap()
            Intent.WhereAmI -> onWhereAmI()
            Intent.Stop -> onStopCommand()
            is Intent.Unknown -> speak("Sorry, I didn't understand that.")
        }
    }

    private fun onNavigateToRoom(roomName: String) {
        val position = currentMapPosition()
        if (position == null) {
            speak("I don't know where I am yet — say \"I'm at the entrance\" first.")
            return
        }
        val guidance = navigationEngine.startNavigationToRoom(mapRepository.currentMap, position, roomName)
        speak(guidance.message)
    }

    private fun onFindObject(label: String) {
        val matches = mapRepository.currentMap.findObjectsByLabel(label)
        if (matches.isEmpty()) {
            speak("I haven't seen a $label yet.")
            return
        }
        val position = currentMapPosition()
        val nearest = if (position != null) {
            matches.minByOrNull { it.position.distanceTo(position) }
        } else {
            matches.maxByOrNull { it.lastSeenEpochMillis }
        }
        val room = nearest?.roomId?.let { mapRepository.currentMap.rooms[it] }
        val distancePhrase = if (position != null && nearest != null) {
            " about %.1f meters away".format(nearest.position.distanceTo(position))
        } else {
            ""
        }
        val roomPhrase = room?.let { " in the ${it.name}" } ?: ""
        speak("$label$roomPhrase$distancePhrase.")
    }

    private fun onNameCurrentRoom(roomName: String) {
        val pose = latestPoseInfo
        val position = currentMapPosition()
        if (pose == null || position == null) {
            speak("I don't know where I am yet, so I can't place this room.")
            return
        }
        val room = getOrCreateRoomAt(mapRepository.currentMap, roomName, position, pose.headingDegrees)
        navigationEngine.rebuildGraph(mapRepository.currentMap)
        speak("Got it. This is the ${room.name}.")
    }

    private fun onConfirmAtEntrance() {
        val entrance = mapRepository.currentMap.findRoomByName("entrance")
        val pose = latestPoseInfo
        if (entrance == null) {
            speak("I don't have a saved entrance yet. Say \"name this room entrance\" first, next time you save a map.")
            return
        }
        if (pose == null) {
            speak("I can't see my own position right now — try again in a moment.")
            return
        }
        relocalizationManager.calibrate(
            SessionPose(pose.x, pose.y, pose.z, pose.headingDegrees),
            entrance.center,
            entrance.referenceHeadingDegrees
        )
        speak("Calibrated. I know where I am now.")
    }

    private fun onStartMapping() {
        mapRepository.startNewMap()
        relocalizationManager.reset()
        navigationEngine.stop()
        navigationEngine.rebuildGraph(mapRepository.currentMap)
        safetyEngine.reset()
        arRenderer.resetFusion()
        speak("Starting a new map. As we walk around, tell me room names — for example, say \"name this room kitchen.\"")
    }

    private fun onSaveMap() {
        mapRepository.save()
        speak("Map saved.")
    }

    private fun onWhereAmI() {
        val position = currentMapPosition()
        if (position == null) {
            speak("I'm not sure yet.")
            return
        }
        val room = nearestRoom(mapRepository.currentMap, position)
        speak(room?.let { "You're in the ${it.name}." } ?: "I don't recognize this room yet.")
    }

    private fun onStopCommand() {
        navigationEngine.stop()
        speechOutput.stopSpeaking()
        speak("Stopped.")
    }

    // ---------------------------------------------------------------------
    // Mapping helpers (Phases 16-20)
    // ---------------------------------------------------------------------

    private fun currentMapPosition(): Vec3? {
        val pose = latestPoseInfo ?: return null
        return relocalizationManager.toMapCoordinates(SessionPose(pose.x, pose.y, pose.z, pose.headingDegrees))
    }

    private fun nearestRoom(map: SemanticMap, position: Vec3): Room? =
        map.rooms.values
            .minByOrNull { it.center.distanceTo(position) }
            ?.takeIf { it.center.distanceTo(position) <= MAX_ROOM_ASSOCIATION_DISTANCE_METERS }

    private fun getOrCreateRoomAt(map: SemanticMap, name: String, position: Vec3, headingDegrees: Float): Room {
        val existingNearby = nearestRoom(map, position)
        if (existingNearby != null) {
            existingNearby.name = name
            existingNearby.referenceHeadingDegrees = headingDegrees
            return existingNearby
        }
        val room = Room(
            id = UUID.randomUUID().toString(),
            name = name,
            center = position,
            referenceHeadingDegrees = headingDegrees
        )
        map.addOrUpdateRoom(room)
        return room
    }

    /**
     * Rough object placement: projects the detection's on-screen horizontal
     * offset into a bearing (using an ASSUMED camera FOV, not the real
     * device characteristic) and its estimated depth into a forward offset
     * from the user's current map-frame position.
     *
     * ASSUMPTION (unverified on-device): heading=0 corresponds to facing
     * the -Z axis in ARCore/OpenGL's convention, increasing heading
     * rotating toward +X. If recalled object positions look consistently
     * mirrored or rotated relative to reality, this is the line to flip -
     * it does not affect room-to-room navigation (which only uses room
     * centers), only the precision of "where is the chair"-style recall.
     */
    private fun estimateObjectPosition(
        userPosition: Vec3,
        userHeadingDegrees: Float,
        track: ObjectTrack,
        imageWidth: Int
    ): Vec3 {
        val distance = track.distanceMeters ?: return userPosition
        val bearingOffsetDegrees = ((track.centerX / imageWidth) - 0.5f) * ASSUMED_HORIZONTAL_FOV_DEGREES
        val totalHeadingRadians = Math.toRadians((userHeadingDegrees + bearingOffsetDegrees).toDouble())
        val forwardX = -sin(totalHeadingRadians).toFloat()
        val forwardZ = -cos(totalHeadingRadians).toFloat()
        return Vec3(
            userPosition.x + forwardX * distance,
            userPosition.y,
            userPosition.z + forwardZ * distance
        )
    }

    // ---------------------------------------------------------------------
    // Model loading (Phase 6) + vision pipeline (Phases 8/12)
    // ---------------------------------------------------------------------

    private fun loadYoloModel() {
        modelStatusText.text = getString(R.string.yolo_loading)
        inferenceExecutor.execute {
            try {
                val detector = YoloDetector(applicationContext)
                yoloDetector = detector

                val pipeline = DetectionPipeline(inferenceExecutor, detector)
                pipeline.onResult = { result -> arRenderer.onDetectionResult(result) }
                arRenderer.detectionPipeline = pipeline

                runOnUiThread {
                    modelStatusText.text = getString(
                        R.string.yolo_loaded,
                        detector.inputTensorCount,
                        detector.outputTensorCount,
                        detector.labels.size
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load YOLO model", e)
                runOnUiThread {
                    modelStatusText.text = getString(
                        R.string.yolo_load_failed,
                        e.javaClass.simpleName,
                        e.message ?: "no message"
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Per-frame UI updates (throttled ~6-7 Hz by ARCoreRenderer)
    // ---------------------------------------------------------------------

    private fun onTelemetryUpdated(poseInfo: PoseInfo, centerDepthMeters: Float?) {
        latestPoseInfo = poseInfo
        showPoseText(poseInfo, centerDepthMeters)
        updateNavigation()
    }

    private fun showPoseText(poseInfo: PoseInfo, centerDepthMeters: Float?) {
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

    private fun updateNavigation() {
        if (!navigationEngine.isNavigating) {
            navigationText.text = "Navigation: idle"
            return
        }
        val position = currentMapPosition() ?: return
        val guidance = navigationEngine.update(position)
        navigationText.text = "Navigation: target=${guidance.targetRoomName} status=${guidance.status}"

        if (!iSightActive || guidance.message.isBlank()) return

        val now = System.nanoTime()
        val shouldAnnounce = guidance.status == NavigationStatus.ARRIVED ||
            now - lastNavigationAnnouncementNanos > NAV_REANNOUNCE_COOLDOWN_NANOS
        if (shouldAnnounce) {
            speechOutput.speak(guidance.message)
            lastNavigationAnnouncementNanos = now
            if (guidance.status == NavigationStatus.ARRIVED) {
                hapticFeedbackManager.play(HapticCue.DESTINATION_REACHED)
            }
        }
    }

    private fun onObjectTracksUpdated(tracks: List<ObjectTrack>, imageWidth: Int, imageHeight: Int) {
        detectionOverlay.update(tracks.map { it.boundingBox }, imageWidth, imageHeight)

        val alert = safetyEngine.evaluate(tracks)
        updateTracksText(tracks, alert)

        if (iSightActive) {
            recordObservedObjects(tracks, imageWidth)
        }
        announceSafety(alert)
    }

    private fun updateTracksText(tracks: List<ObjectTrack>, alert: SafetyAlert) {
        val topTracks = tracks.sortedBy { it.distanceMeters ?: Float.MAX_VALUE }.take(5)
        val lines = topTracks.joinToString("\n") { track ->
            val distance = track.distanceMeters?.let { "%.1fm".format(it) } ?: "?"
            "%s %.2f  %s  %s  id=%d".format(track.label, track.confidence, distance, track.direction.name, track.trackId)
        }
        tracksText.text = buildString {
            append("Safety: ${alert.tier}")
            if (alert.message.isNotBlank()) append(" - ${alert.message}")
            if (lines.isNotEmpty()) {
                append('\n')
                append(lines)
            }
        }
    }

    private fun recordObservedObjects(tracks: List<ObjectTrack>, imageWidth: Int) {
        val pose = latestPoseInfo ?: return
        val position = currentMapPosition() ?: return
        val map = mapRepository.currentMap
        val room = nearestRoom(map, position)

        for (track in tracks) {
            val objectPosition = estimateObjectPosition(position, pose.headingDegrees, track, imageWidth)
            map.upsertObject(
                MapObject(
                    id = "obj_${track.trackId}",
                    label = track.label,
                    position = objectPosition,
                    confidence = track.confidence,
                    roomId = room?.id,
                    lastSeenEpochMillis = System.currentTimeMillis(),
                    isStatic = true
                )
            )
        }

        // Room-type suggestion is informational ONLY, never auto-committed -
        // per this project's safety rule for uncertain room classification.
        if (room == null) {
            val suggestion = RoomClassifier.suggestRoomType(tracks.map { it.label })
            if (suggestion != null) {
                Log.d(TAG, "RoomClassifier suggests '$suggestion' near current position (not auto-applied)")
            }
        }
    }

    /**
     * Ambient safety announcements. Deliberately does NOT speak WHITE-tier
     * ("object detected, no concern") continuously — narrating every single
     * detected object nonstop would bury the alerts that actually matter.
     * That informational stream stays in [tracksText] for development use;
     * BLUE/RED are the tiers that actually get voiced/vibrated.
     */
    private fun announceSafety(alert: SafetyAlert) {
        if (!iSightActive) {
            lastSpokenSafetyTier = alert.tier
            return
        }

        val now = System.nanoTime()
        val tierChanged = alert.tier != lastSpokenSafetyTier
        val cooldownElapsed = now - lastSafetyAnnouncementNanos > SAFETY_REANNOUNCE_COOLDOWN_NANOS

        when (alert.tier) {
            SafetyTier.RED -> {
                if (tierChanged || cooldownElapsed) {
                    speechOutput.speak(alert.message, flushQueue = true)
                    hapticFeedbackManager.play(HapticCue.FRONT_OBSTACLE)
                    lastSafetyAnnouncementNanos = now
                }
            }
            SafetyTier.BLUE -> {
                if (tierChanged || cooldownElapsed) {
                    speechOutput.speak(alert.message)
                    lastSafetyAnnouncementNanos = now
                }
            }
            SafetyTier.WHITE -> Unit
        }
        lastSpokenSafetyTier = alert.tier
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

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

        mapRepository.save()

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
        speechInput.cancel()
        speechOutput.shutdown()
        inferenceExecutor.shutdown()
        yoloDetector?.close()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
