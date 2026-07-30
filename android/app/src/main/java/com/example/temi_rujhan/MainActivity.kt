package com.example.temi_rujhan

// v7: v6 + listen (speech-to-text)
// New: "photo" command opens the front camera, captures one JPEG, releases
// the camera, and publishes the raw bytes to temi/status/photo (retained).
// Uses the legacy android.hardware.Camera API on purpose — it works on
// Temi's old Android versions and needs zero new Gradle dependencies.

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.robotemi.sdk.Robot
import com.robotemi.sdk.SttLanguage
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnDetectionStateChangedListener
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnLocationsUpdatedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.listeners.OnUserInteractionChangedListener
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION") // legacy Camera API is intentional (old Android on Temi)
class MainActivity : AppCompatActivity(),
    OnRobotReadyListener,
    OnGoToLocationStatusChangedListener,
    OnDetectionStateChangedListener,
    OnUserInteractionChangedListener,
    OnLocationsUpdatedListener,
    Robot.AsrListener {

    companion object {
        // Your laptop's IP (runs the Mosquitto broker)
        const val BROKER_URI = "tcp://192.168.0.38:1883"
        const val CLIENT_ID = "temi-bridge-01"
        const val PUBLISH_INTERVAL_MS = 2000L
        const val TAG = "TemiBridge"
        const val CAMERA_PERMISSION_REQUEST = 42
    }

    private val robot: Robot by lazy { Robot.getInstance() }
    private var mqtt: MqttClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var statusText: TextView
    private var capturing = false // one capture at a time

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusText = TextView(this).apply {
            textSize = 22f
            setPadding(48, 48, 48, 48)
            text = "Temi Bridge v7\nWaiting for robot..."
        }
        setContentView(statusText)

        // NEW in v5: ask for camera permission on first launch.
        // Someone taps "Allow" on Temi's screen once; Android remembers it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST
            )
        }
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
        robot.addOnDetectionStateChangedListener(this)
        robot.addOnUserInteractionChangedListener(this)
        robot.addOnLocationsUpdatedListener(this)
        robot.addAsrListener(this) // NEW in v7
    }

    override fun onStop() {
        robot.removeOnRobotReadyListener(this)
        robot.removeOnGoToLocationStatusChangedListener(this)
        robot.removeOnDetectionStateChangedListener(this)
        robot.removeOnUserInteractionChangedListener(this)
        robot.removeOnLocationsUpdateListener(this) // note: SDK names this without the 'd' in Update
        robot.removeAsrListener(this) // NEW in v7
        super.onStop()
    }

    override fun onRobotReady(isReady: Boolean) {
        if (!isReady) return
        showStatus("Robot ready. Connecting to broker...")
        scope.launch { connectAndPublish() }
    }

    // ── event listeners ─────────────────────────────────────────

    override fun onGoToLocationStatusChanged(
        location: String, status: String, descriptionId: Int, description: String
    ) {
        publishEvent("temi/status/nav", JSONObject()
            .put("location", location)
            .put("status", status)
            .put("description", description))
    }

    override fun onDetectionStateChanged(state: Int) {
        val label = when (state) {
            2 -> "detected"
            1 -> "lost"
            else -> "idle"
        }
        publishEvent("temi/status/detection", JSONObject().put("state", label))
    }

    override fun onUserInteraction(isInteracting: Boolean) {
        publishEvent("temi/status/interaction", JSONObject().put("interacting", isInteracting))
    }

    override fun onLocationsUpdated(locations: List<String>) {
        publishLocations(locations)
    }

    // NEW in v7: fires when temi's speech recognition produces text.
    // NOTE: depending on your SDK version the signature may include a second
    // parameter (sttLanguage). If Android Studio underlines this in red,
    // delete this function, put the cursor on the class name, press
    // Alt+Insert -> Implement Members, and let the IDE generate the correct
    // signature — then move the body below into it.
//    override fun onAsrResult(asrResult: String, sttLanguage: com.robotemi.sdk.constants.SttLanguage) {
//        showStatus("Heard: \"$asrResult\"")
//        publishEvent("temi/status/asr", JSONObject()
//            .put("text", asrResult)
//            .put("timestamp", System.currentTimeMillis()))
//        // Close the assistant overlay so the conversation doesn't hang open
//        robot.finishConversation()
//    }

    private fun publishLocations(locations: List<String>) {
        publishEvent("temi/status/locations", JSONObject()
            .put("locations", JSONArray(locations)))
    }

    private fun publishEvent(topic: String, payload: JSONObject) {
        scope.launch {
            try {
                mqtt?.takeIf { it.isConnected }?.publish(
                    topic,
                    MqttMessage(payload.toString().toByteArray()).apply { isRetained = true }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Event publish error: ${e.message}")
            }
        }
    }

    // ── NEW in v5: photo capture ────────────────────────────────

    private fun capturePhoto() {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                showStatus("Photo failed: camera permission not granted on Temi")
                return@runOnUiThread
            }
            if (capturing) return@runOnUiThread
            capturing = true
            showStatus("Capturing photo...")

            var camera: android.hardware.Camera? = null
            try {
                camera = android.hardware.Camera.open(findFrontCameraId())

                // Keep the JPEG small enough for MQTT + browser:
                // pick the supported picture size closest to 1024px wide.
                val params = camera.parameters
                val size = params.supportedPictureSizes
                    .minByOrNull { kotlin.math.abs(it.width - 1024) }
                if (size != null) params.setPictureSize(size.width, size.height)
                params.jpegQuality = 75
                camera.parameters = params

                // The camera needs a preview target even for a single shot;
                // an off-screen SurfaceTexture satisfies it without any UI.
                camera.setPreviewTexture(SurfaceTexture(10))
                camera.startPreview()

                // Give auto-exposure a moment, then shoot.
                statusText.postDelayed({
                    try {
                        camera.takePicture(null, null) { jpegBytes, cam ->
                            try { cam.stopPreview(); cam.release() } catch (_: Exception) {}
                            capturing = false
                            showStatus("Photo captured (${jpegBytes.size / 1024} KB), publishing...")
                            scope.launch {
                                try {
                                    // Raw JPEG bytes as the payload — not JSON.
                                    mqtt?.takeIf { it.isConnected }?.publish(
                                        "temi/status/photo",
                                        MqttMessage(jpegBytes).apply { isRetained = true }
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Photo publish error: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "takePicture error: ${e.message}")
                        try { camera.release() } catch (_: Exception) {}
                        capturing = false
                        showStatus("Photo failed: ${e.message}")
                    }
                }, 400)
            } catch (e: Exception) {
                Log.e(TAG, "Camera error: ${e.message}")
                try { camera?.release() } catch (_: Exception) {}
                capturing = false
                showStatus("Photo failed: ${e.message}\n(is another app using the camera?)")
            }
        }
    }

    private fun findFrontCameraId(): Int {
        val info = android.hardware.Camera.CameraInfo()
        for (i in 0 until android.hardware.Camera.getNumberOfCameras()) {
            android.hardware.Camera.getCameraInfo(i, info)
            if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return 0 // fall back to the first camera
    }

    // ── connection + command handling ───────────────────────────

    private suspend fun connectAndPublish() {
        while (scope.isActive) {
            try {
                if (mqtt?.isConnected != true) {
                    mqtt = MqttClient(BROKER_URI, CLIENT_ID, MemoryPersistence()).also {
                        it.connect(MqttConnectOptions().apply {
                            isCleanSession = true
                            connectionTimeout = 5
                            isAutomaticReconnect = false
                        })
                        it.subscribe("temi/command/#") { topic, message ->
                            handleCommand(topic, message)
                        }
                    }
                    showStatus("Connected to $BROKER_URI\nPublishing status, listening for commands...")
                    publishLocations(robot.locations)
                }
                publishStatus()
            } catch (e: Exception) {
                Log.e(TAG, "MQTT error: ${e.message}")
                showStatus("Broker unreachable, retrying...\n(${e.message})")
                try { mqtt?.close() } catch (_: Exception) {}
                mqtt = null
                delay(5000)
                continue
            }
            delay(PUBLISH_INTERVAL_MS)
        }
    }

    private fun handleCommand(topic: String, message: MqttMessage) {
        try {
            val command = topic.substringAfterLast("/")
            Log.i(TAG, "Command received: $command")

            when (command) {
                "speak" -> {
                    val payload = JSONObject(String(message.payload))
                    val text = payload.getString("text")
                    showStatus("Speaking: \"$text\"")
                    runOnUiThread {
                        robot.speak(TtsRequest.create(text, false))
                    }
                }
                "goto" -> {
                    val payload = JSONObject(String(message.payload))
                    val location = payload.getString("location")
                    showStatus("Going to: $location")
                    runOnUiThread {
                        robot.goTo(location)
                    }
                }
                "stop" -> {
                    showStatus("STOP requested")
                    runOnUiThread {
                        robot.stopMovement()
                    }
                }
                // NEW in v5
                "photo" -> {
                    runOnUiThread {
                        robot.speak(TtsRequest.create("1, 2, 3, smile!", false))
                    }
                    capturePhoto()
                }
                // NEW in v6: rotate in place by a relative number of degrees
                // (positive = counterclockwise, negative = clockwise)
                "turn" -> {
                    val degrees = JSONObject(String(message.payload)).getInt("degrees")
                    showStatus("Turning by $degrees°")
                    runOnUiThread {
                        robot.turnBy(degrees)
                    }
                }
                // NEW in v6: set head tilt to an absolute angle (-25..55)
                "tilt" -> {
                    val angle = JSONObject(String(message.payload)).getInt("angle")
                        .coerceIn(-25, 55) // never send an out-of-range angle
                    showStatus("Tilting head to $angle°")
                    runOnUiThread {
                        robot.tiltAngle(angle)
                    }
                }
                // NEW in v7: trigger temi's listening (same as "Hey temi")
                "listen" -> {
                    showStatus("Listening...")
                    runOnUiThread {
                        robot.wakeup()
                    }
                }
                else -> Log.w(TAG, "Unknown command: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command error: ${e.message}")
        }
    }

    private fun publishStatus() {
        val client = mqtt ?: return

        robot.batteryData?.let { b ->
            // NEW in v10: Android's battery broadcast is "sticky" — passing a
            // null receiver just returns the latest one, no registration
            // needed. Temperature arrives in tenths of a degree Celsius.
            val batteryIntent = registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val tempTenths = batteryIntent?.getIntExtra(
                android.os.BatteryManager.EXTRA_TEMPERATURE, -1
            ) ?: -1

            val payload = JSONObject()
                .put("level", b.level)
                .put("charging", b.isCharging)
            if (tempTenths > 0) payload.put("temp", tempTenths / 10.0)
            client.publish("temi/status/battery", MqttMessage(payload.toString().toByteArray()))
        }

        robot.getPosition().let { p ->
            val payload = JSONObject()
                .put("x", p.x)
                .put("y", p.y)
                .put("yaw", p.yaw)
            client.publish("temi/status/position", MqttMessage(payload.toString().toByteArray()))
        }

        val info = JSONObject()
            .put("serial", robot.serialNumber ?: "unknown")
            .put("timestamp", System.currentTimeMillis())
        client.publish("temi/status/heartbeat", MqttMessage(info.toString().toByteArray()))
    }

    private fun showStatus(msg: String) {
        runOnUiThread { statusText.text = "Temi Bridge v7\n\n$msg" }
    }

    override fun onDestroy() {
        scope.cancel()
        try { mqtt?.disconnect() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAsrResult(asrResult: String, sttLanguage: SttLanguage) {
        showStatus("Heard: \"$asrResult\"")
        publishEvent("temi/status/asr", JSONObject()
            .put("text", asrResult)
            .put("timestamp", System.currentTimeMillis()))
        robot.finishConversation()
    }
}