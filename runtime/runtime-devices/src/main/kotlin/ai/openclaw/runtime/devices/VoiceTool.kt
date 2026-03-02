package ai.openclaw.runtime.devices

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume

/**
 * Agent tool for speech-to-text transcription using Android SpeechRecognizer.
 */
class VoiceTool(
    private val context: Context,
) : AgentTool {

    override val name: String = "voice_input"

    override val description: String =
        "Record audio from the device microphone and transcribe it to text " +
        "using the on-device speech recognizer."

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "duration_ms": {
              "type": "integer",
              "description": "Maximum listening duration in milliseconds (default 10000, max 60000)."
            },
            "language": {
              "type": "string",
              "description": "Language code for recognition (e.g. 'en-US', 'es-ES'). Default: device language."
            }
          }
        }
    """.trimIndent()

    override suspend fun execute(input: String, context: ToolContext): String {
        val missing = this.context.missingPermissions(DevicePermission.RECORD_AUDIO)
        if (missing.isNotEmpty()) {
            return "Error: RECORD_AUDIO permission not granted."
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this.context)) {
            return "Error: Speech recognition is not available on this device."
        }

        val params = Json.parseToJsonElement(input).jsonObject
        val durationMs = params["duration_ms"]?.jsonPrimitive?.longOrNull?.coerceIn(1000, 60000) ?: 10000L
        val language = params["language"]?.jsonPrimitive?.content

        return try {
            val result = withTimeout(durationMs + 5000) {
                recognizeSpeech(language)
            }
            result
        } catch (e: Exception) {
            "Error: Speech recognition failed: ${e.message}"
        }
    }

    private suspend fun recognizeSpeech(language: String?): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                if (language != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognizer.destroy()

                    if (matches.isNullOrEmpty()) {
                        cont.resume("""{"status":"ok","text":"","alternatives":[],"message":"No speech detected."}""")
                    } else {
                        val primary = matches[0].replace("\"", "\\\"")
                        val alternatives = matches.drop(1).joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
                        cont.resume("""{"status":"ok","text":"$primary","alternatives":[$alternatives]}""")
                    }
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error ($error)"
                    }
                    cont.resume("""{"status":"error","error_code":$error,"message":"$errorMsg"}""")
                }
            })

            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
}
