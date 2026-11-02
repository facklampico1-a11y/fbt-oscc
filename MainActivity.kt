package com.example.fbtosc

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Size
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.atan2

class MainActivity : ComponentActivity() {

    private lateinit var ipField: EditText
    private lateinit var heightField: EditText
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var previewView: PreviewView

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var landmarker: PoseLandmarker? = null
    private var socket: DatagramSocket? = null
    private var targetIp = ""
    private var hipHeight = 0.9f
    private var running = false
    private var frames = 0
    private val state = HashMap<String, Float>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else status.text = "Kamera-Berechtigung fehlt."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        ipField = EditText(this).apply {
            hint = "Quest-IP (z. B. 192.168.0.42)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        heightField = EditText(this).apply {
            hint = "Körpergröße in cm"
            setText("170")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        button = Button(this).apply {
            text = "Start"
            setOnClickListener { if (running) stop() else requestStart() }
        }
        status = TextView(this).apply { text = "Bereit." }
        previewView = PreviewView(this)

        root.addView(ipField)
        root.addView(heightField)
        root.addView(button)
        root.addView(status)
        root.addView(
            previewView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700)
        )
        setContentView(root)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun requestStart() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun start() {
        targetIp = ipField.text.toString().trim()
        val cm = heightField.text.toString().toFloatOrNull() ?: 170f
        hipHeight = 0.53f * cm / 100f
        if (targetIp.isEmpty()) {
            status.text = "Bitte Quest-IP eingeben."
            return
        }

        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .setDelegate(Delegate.CPU)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .build()
            landmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            status.text = "Modell-Fehler: ${e.message}"
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
            )
            running = true
            button.text = "Stop"
            status.text = "Läuft. Stell dich ganz ins Bild."
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stop() {
        running = false
        provider?.unbindAll()
        executor.execute {
            landmarker?.close()
            landmarker = null
            socket?.close()
            socket = null
        }
        if (::button.isInitialized) button.text = "Start"
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val raw = proxy.toBitmap()
            val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)

            val result = landmarker?.detectForVideo(
                BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis()
            )
            val pose = result?.worldLandmarks()?.firstOrNull()
            if (pose != null && pose.size >= 33) {
                sendPose(pose)
                frames++
                if (frames % 15 == 0) postStatus("Sende Tracker (${frames} Frames)")
            } else if (frames % 15 == 0) {
                postStatus("Keine Person erkannt.")
            }
        } catch (e: Exception) {
            postStatus("Fehler: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    private fun postStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    // MediaPipe (x rechts im Bild, y unten, z weg von der Kamera) -> VRChat (x rechts, y oben, z vorwärts)
    private fun point(w: List<Landmark>, i: Int) =
        floatArrayOf(-w[i].x(), -w[i].y(), -w[i].z())

    private fun sendPose(w: List<Landmark>) {
        val leftHip = point(w, 23)
        val rightHip = point(w, 24)
        val leftAnkle = point(w, 27)
        val rightAnkle = point(w, 28)
        val leftToe = point(w, 31)
        val rightToe = point(w, 32)

        val hipYaw = yaw("hip", -(rightHip[2] - leftHip[2]), rightHip[0] - leftHip[0])
        val leftYaw = yaw("lf", leftToe[0] - leftAnkle[0], leftToe[2] - leftAnkle[2])
        val rightYaw = yaw("rf", rightToe[0] - rightAnkle[0], rightToe[2] - rightAnkle[2])

        sendTracker(1, 0f, hipHeight, 0f, hipYaw)
        sendTracker(2, ema("lx", leftAnkle[0]), ema("ly", hipHeight + leftAnkle[1]), ema("lz", leftAnkle[2]), leftYaw)
        sendTracker(3, ema("rx", rightAnkle[0]), ema("ry", hipHeight + rightAnkle[1]), ema("rz", rightAnkle[2]), rightYaw)
    }

    private fun ema(key: String, value: Float, alpha: Float = 0.35f): Float {
        val old = state[key] ?: value
        val next = old + alpha * (value - old)
        state[key] = next
        return next
    }

    private fun yaw(key: String, fx: Float, fz: Float): Float {
        val value = Math.toDegrees(atan2(fx, fz).toDouble()).toFloat()
        val old = state[key] ?: value
        var diff = value - old
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        val next = old + 0.35f * diff
        state[key] = next
        return next
    }

    private fun sendTracker(id: Int, x: Float, y: Float, z: Float, yawDeg: Float) {
        send(osc("/tracking/trackers/$id/position", x, y, z))
        send(osc("/tracking/trackers/$id/rotation", 0f, yawDeg, 0f))
    }

    private fun send(data: ByteArray) {
        val s = socket ?: DatagramSocket().also { socket = it }
        s.send(DatagramPacket(data, data.size, InetAddress.getByName(targetIp), 9000))
    }

    private fun osc(address: String, vararg values: Float): ByteArray {
        val out = ByteArrayOutputStream()
        fun writePadded(text: String) {
            out.write(text.toByteArray())
            out.write(0)
            while (out.size() % 4 != 0) out.write(0)
        }
        writePadded(address)
        writePadded("," + "f".repeat(values.size))
        val buffer = ByteBuffer.allocate(4 * values.size).order(ByteOrder.BIG_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        out.write(buffer.array())
        return out.toByteArray()
    }
}
