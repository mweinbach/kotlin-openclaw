package ai.openclaw.runtime.devices

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.core.model.TtsConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Agent tool for text-to-speech synthesis.
 * Supports Android's built-in TTS engine and optional ElevenLabs API integration.
 */
class TtsTool(
    private val context: Context,
    private val ttsConfig: TtsConfig? = null,
    private val outputDir: File = context.filesDir.resolve("tts_output"),
) : AgentTool {

    override val name: String = "tts"

    override val description: String =
        "Convert text to speech. Uses the device TTS engine by default, " +
        "or ElevenLabs API if configured. Returns the audio file path."

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "text": {
              "type": "string",
              "description": "Text to convert to speech."
            },
            "provider": {
              "type": "string",
              "enum": ["local", "elevenlabs"],
              "description": "TTS provider: 'local' for device TTS, 'elevenlabs' for ElevenLabs API. Default: local."
            },
            "voice": {
              "type": "string",
              "description": "Voice identifier. For local: language tag (e.g. 'en-US'). For ElevenLabs: voice ID."
            },
            "language": {
              "type": "string",
              "description": "Language code (e.g. 'en', 'es', 'fr'). Used for local TTS."
            }
          },
          "required": ["text"]
        }
    """.trimIndent()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    init {
        outputDir.mkdirs()
    }

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = Json.parseToJsonElement(input).jsonObject
        val text = params["text"]?.jsonPrimitive?.content
            ?: return "Error: 'text' is required."
        val provider = params["provider"]?.jsonPrimitive?.content ?: "local"
        val voice = params["voice"]?.jsonPrimitive?.content
        val language = params["language"]?.jsonPrimitive?.content

        val maxLen = ttsConfig?.maxTextLength ?: 4096
        if (text.length > maxLen) {
            return "Error: Text exceeds maximum length of $maxLen characters."
        }

        return when (provider) {
            "elevenlabs" -> synthesizeElevenLabs(text, voice)
            else -> synthesizeLocal(text, voice, language)
        }
    }

    private suspend fun synthesizeLocal(text: String, voice: String?, language: String?): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "tts_local_$timestamp.wav")
        val utteranceId = UUID.randomUUID().toString()

        return try {
            val tts = initLocalTts()

            // Set language
            val locale = when {
                voice != null -> Locale.forLanguageTag(voice)
                language != null -> Locale.forLanguageTag(language)
                else -> Locale.getDefault()
            }
            tts.language = locale

            val timeoutMs = ttsConfig?.timeoutMs?.toLong() ?: 30000L

            val result = withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) cont.resume("ok")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) cont.resume("error")
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) cont.resume("error:$errorCode")
                        }
                    })

                    val synthResult = tts.synthesizeToFile(text, null, outputFile, utteranceId)
                    if (synthResult != TextToSpeech.SUCCESS) {
                        cont.resume("error:synth_failed")
                    }
                }
            }

            tts.shutdown()

            if (result == "ok" && outputFile.exists()) {
                """{"status":"ok","file":"${outputFile.absolutePath}","provider":"local","type":"audio/wav"}"""
            } else {
                "Error: Local TTS synthesis failed: $result"
            }
        } catch (e: Exception) {
            "Error: Local TTS failed: ${e.message}"
        }
    }

    private suspend fun initLocalTts(): TextToSpeech =
        suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(this.context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(engine!!)
                } else {
                    cont.resume(engine!!)
                }
            }
        }

    private fun synthesizeElevenLabs(text: String, voiceId: String?): String {
        val elevenLabsConfig = ttsConfig?.elevenlabs
        val apiKey = elevenLabsConfig?.apiKey
            ?: return "Error: ElevenLabs API key not configured in tts.elevenlabs.apiKey"

        val effectiveVoiceId = voiceId
            ?: elevenLabsConfig.voiceId
            ?: return "Error: No voice ID provided and none configured in tts.elevenlabs.voiceId"

        val baseUrl = elevenLabsConfig.baseUrl ?: "https://api.elevenlabs.io"
        val modelId = elevenLabsConfig.modelId ?: "eleven_monolingual_v1"

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "tts_elevenlabs_$timestamp.mp3")

        val bodyJson = buildString {
            append("""{"text":""")
            append(Json.encodeToString(kotlinx.serialization.serializer<String>(), text))
            append(""","model_id":"$modelId"""")

            elevenLabsConfig.voiceSettings?.let { vs ->
                append(""","voice_settings":{""")
                val parts = mutableListOf<String>()
                vs.stability?.let { parts.add(""""stability":$it""") }
                vs.similarityBoost?.let { parts.add(""""similarity_boost":$it""") }
                vs.style?.let { parts.add(""""style":$it""") }
                vs.useSpeakerBoost?.let { parts.add(""""use_speaker_boost":$it""") }
                vs.speed?.let { parts.add(""""speed":$it""") }
                append(parts.joinToString(","))
                append("}")
            }

            append("}")
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-speech/$effectiveVoiceId")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "Error: ElevenLabs API returned ${response.code}: ${response.body?.string()?.take(200)}"
                }

                response.body?.byteStream()?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                """{"status":"ok","file":"${outputFile.absolutePath}","provider":"elevenlabs","type":"audio/mpeg"}"""
            }
        } catch (e: Exception) {
            "Error: ElevenLabs request failed: ${e.message}"
        }
    }
}
