package ai.openclaw.runtime.devices

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Agent tool for capturing the device screen via MediaProjection API.
 *
 * IMPORTANT: Screen capture requires a MediaProjection token obtained from an Activity result.
 * The host Activity must call [setMediaProjectionResult] before this tool can be used.
 */
class ScreenCaptureTool(
    private val context: Context,
    private val outputDir: File = context.filesDir.resolve("screen_captures"),
) : AgentTool {

    override val name: String = "screen_capture"

    override val description: String =
        "Capture a screenshot of the device screen. " +
        "Requires screen capture permission to be pre-approved by the user."

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "format": {
              "type": "string",
              "enum": ["png", "jpeg"],
              "description": "Image format (default: png)."
            },
            "quality": {
              "type": "integer",
              "description": "JPEG quality 1-100 (default: 90). Ignored for PNG."
            }
          }
        }
    """.trimIndent()

    @Volatile
    private var projectionResultCode: Int = Activity.RESULT_CANCELED

    @Volatile
    private var projectionData: Intent? = null

    init {
        outputDir.mkdirs()
    }

    /**
     * Must be called from the host Activity after the user grants screen capture permission.
     * Typically done in onActivityResult for the MediaProjection permission request.
     */
    fun setMediaProjectionResult(resultCode: Int, data: Intent?) {
        projectionResultCode = resultCode
        projectionData = data
    }

    override suspend fun execute(input: String, context: ToolContext): String {
        if (projectionResultCode != Activity.RESULT_OK || projectionData == null) {
            return "Error: Screen capture permission not granted. The user must approve screen capture first."
        }

        val params = Json.parseToJsonElement(input).jsonObject
        val format = params["format"]?.let {
            it.toString().trim('"')
        } ?: "png"
        val quality = params["quality"]?.toString()?.toIntOrNull()?.coerceIn(1, 100) ?: 90

        return try {
            val file = captureScreen(format, quality)
            val mimeType = if (format == "jpeg") "image/jpeg" else "image/png"
            """{"status":"ok","file":"${file.absolutePath}","type":"$mimeType"}"""
        } catch (e: Exception) {
            "Error: Screen capture failed: ${e.message}"
        }
    }

    private suspend fun captureScreen(format: String, quality: Int): File {
        val projectionManager = this.context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(projectionResultCode, projectionData!!)

        val windowManager = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extension = if (format == "jpeg") "jpg" else "png"
        val outputFile = File(outputDir, "screenshot_$timestamp.$extension")

        return withTimeout(10000) {
            captureFrame(projection, width, height, density, outputFile, format, quality)
        }
    }

    private suspend fun captureFrame(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
        outputFile: File,
        format: String,
        quality: Int,
    ): File = suspendCancellableCoroutine { cont ->
        val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to exact screen size
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (croppedBitmap !== bitmap) bitmap.recycle()

                FileOutputStream(outputFile).use { fos ->
                    val compressFormat = if (format == "jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    croppedBitmap.compress(compressFormat, quality, fos)
                }
                croppedBitmap.recycle()

                virtualDisplay?.release()
                projection.stop()
                cont.resume(outputFile)
            } catch (e: Exception) {
                virtualDisplay?.release()
                projection.stop()
                cont.resume(outputFile)
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null,
        )

        cont.invokeOnCancellation {
            virtualDisplay?.release()
            projection.stop()
            imageReader.close()
        }
    }
}
