package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- TTS Config (ported from src/config/types.tts.ts) ---

@Serializable
enum class TtsProvider {
    @SerialName("elevenlabs") ELEVENLABS,
    @SerialName("openai") OPENAI,
    @SerialName("edge") EDGE,
}

@Serializable
enum class TtsMode {
    @SerialName("final") FINAL,
    @SerialName("all") ALL,
}

@Serializable
data class TtsModelOverrideConfig(
    val enabled: Boolean? = null,
    val allowText: Boolean? = null,
    val allowProvider: Boolean? = null,
    val allowVoice: Boolean? = null,
    val allowModelId: Boolean? = null,
    val allowVoiceSettings: Boolean? = null,
    val allowNormalization: Boolean? = null,
    val allowSeed: Boolean? = null,
)

@Serializable
data class ElevenLabsVoiceSettings(
    val stability: Double? = null,
    val similarityBoost: Double? = null,
    val style: Double? = null,
    val useSpeakerBoost: Boolean? = null,
    val speed: Double? = null,
)

@Serializable
data class ElevenLabsConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val voiceId: String? = null,
    val modelId: String? = null,
    val seed: Int? = null,
    val applyTextNormalization: String? = null,
    val languageCode: String? = null,
    val voiceSettings: ElevenLabsVoiceSettings? = null,
)

@Serializable
data class OpenAiTtsConfig(
    val apiKey: String? = null,
    val model: String? = null,
    val voice: String? = null,
)

@Serializable
data class EdgeTtsConfig(
    val enabled: Boolean? = null,
    val voice: String? = null,
    val lang: String? = null,
    val outputFormat: String? = null,
    val pitch: String? = null,
    val rate: String? = null,
    val volume: String? = null,
    val saveSubtitles: Boolean? = null,
    val proxy: String? = null,
    val timeoutMs: Int? = null,
)

@Serializable
data class TtsConfig(
    val auto: TtsAutoMode? = null,
    val enabled: Boolean? = null,
    val mode: TtsMode? = null,
    val provider: TtsProvider? = null,
    val summaryModel: String? = null,
    val modelOverrides: TtsModelOverrideConfig? = null,
    val elevenlabs: ElevenLabsConfig? = null,
    val openai: OpenAiTtsConfig? = null,
    val edge: EdgeTtsConfig? = null,
    val prefsPath: String? = null,
    val maxTextLength: Int? = null,
    val timeoutMs: Int? = null,
)
