package ai.openclaw.runtime.devices

import android.content.Context
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Agent tool for camera capture (photo and video) using CameraX.
 */
class CameraTool(
    private val context: Context,
    private val outputDir: File = context.filesDir.resolve("camera_output"),
) : AgentTool {

    override val name: String = "camera"

    override val description: String =
        "Capture a photo or record a short video using the device camera. " +
        "Returns the file path of the captured media."

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["photo", "video"],
              "description": "Whether to take a photo or record a video."
            },
            "duration_ms": {
              "type": "integer",
              "description": "Video recording duration in milliseconds (default 5000, max 30000). Ignored for photos."
            },
            "camera": {
              "type": "string",
              "enum": ["back", "front"],
              "description": "Which camera to use (default: back)."
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    init {
        outputDir.mkdirs()
    }

    override suspend fun execute(input: String, context: ToolContext): String {
        val missing = this.context.missingPermissions(DevicePermission.CAMERA)
        if (missing.isNotEmpty()) {
            return "Error: Camera permission not granted. Required: ${missing.joinToString { it.manifestPermission }}"
        }

        val params = Json.parseToJsonElement(input).jsonObject
        val action = params["action"]?.jsonPrimitive?.content ?: "photo"
        val durationMs = params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        val cameraFacing = params["camera"]?.jsonPrimitive?.content ?: "back"

        return when (action) {
            "photo" -> takePhoto(cameraFacing)
            "video" -> recordVideo(cameraFacing, durationMs.coerceIn(1000, 30000))
            else -> "Error: Unknown action '$action'. Use 'photo' or 'video'."
        }
    }

    private suspend fun takePhoto(facing: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "IMG_$timestamp.jpg")
        val cameraSelector = resolveCameraSelector(facing)

        return try {
            val cameraProvider = getCameraProvider()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                NoOpLifecycleOwner,
                cameraSelector,
                imageCapture,
            )

            val savedFile = captureImage(imageCapture, outputFile)
            cameraProvider.unbindAll()

            """{"status":"ok","file":"${savedFile.absolutePath}","type":"image/jpeg"}"""
        } catch (e: Exception) {
            "Error: Failed to capture photo: ${e.message}"
        }
    }

    private suspend fun recordVideo(facing: String, durationMs: Long): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "VID_$timestamp.mp4")
        val cameraSelector = resolveCameraSelector(facing)

        val audioMissing = this.context.missingPermissions(DevicePermission.RECORD_AUDIO)

        return try {
            val cameraProvider = getCameraProvider()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                NoOpLifecycleOwner,
                cameraSelector,
                videoCapture,
            )

            val outputOptions = FileOutputOptions.Builder(outputFile).build()
            val pendingRecording = if (audioMissing.isEmpty()) {
                recorder.prepareRecording(this.context, outputOptions).withAudioEnabled()
            } else {
                recorder.prepareRecording(this.context, outputOptions)
            }

            val resultFile = recordForDuration(pendingRecording, durationMs)
            cameraProvider.unbindAll()

            """{"status":"ok","file":"${resultFile.absolutePath}","type":"video/mp4","duration_ms":$durationMs}"""
        } catch (e: Exception) {
            "Error: Failed to record video: ${e.message}"
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(this.context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(this.context),
            )
        }

    private suspend fun captureImage(imageCapture: ImageCapture, outputFile: File): File =
        suspendCancellableCoroutine { cont ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        cont.resume(outputFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resume(outputFile) // file may not exist
                    }
                },
            )
        }

    @Suppress("MissingPermission") // checked at call site
    private suspend fun recordForDuration(
        pendingRecording: androidx.camera.video.PendingRecording,
        durationMs: Long,
    ): File {
        var recording: Recording? = null
        return try {
            withTimeout(durationMs + 2000) {
                suspendCancellableCoroutine { cont ->
                    recording = pendingRecording.start(
                        ContextCompat.getMainExecutor(context),
                    ) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            cont.resume(event.outputResults.outputUri.path?.let { File(it) }
                                ?: File(""))
                        }
                    }

                    // Schedule stop after duration
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        recording?.stop()
                    }, durationMs)

                    cont.invokeOnCancellation { recording?.stop() }
                }
            }
        } catch (e: Exception) {
            recording?.stop()
            throw e
        }
    }

    private fun resolveCameraSelector(facing: String): CameraSelector =
        if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
}
