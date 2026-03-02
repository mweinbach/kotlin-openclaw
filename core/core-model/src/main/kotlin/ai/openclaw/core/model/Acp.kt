package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- ACP Runtime Types (ported from src/acp/runtime/types.ts) ---

@Serializable
enum class AcpRuntimePromptMode {
    @SerialName("prompt") PROMPT,
    @SerialName("steer") STEER,
}

@Serializable
enum class AcpRuntimeSessionMode {
    @SerialName("persistent") PERSISTENT,
    @SerialName("oneshot") ONESHOT,
}

@Serializable
enum class AcpSessionUpdateTag {
    @SerialName("agent_message_chunk") AGENT_MESSAGE_CHUNK,
    @SerialName("agent_thought_chunk") AGENT_THOUGHT_CHUNK,
    @SerialName("tool_call") TOOL_CALL,
    @SerialName("tool_call_update") TOOL_CALL_UPDATE,
    @SerialName("usage_update") USAGE_UPDATE,
    @SerialName("available_commands_update") AVAILABLE_COMMANDS_UPDATE,
    @SerialName("current_mode_update") CURRENT_MODE_UPDATE,
    @SerialName("config_option_update") CONFIG_OPTION_UPDATE,
    @SerialName("session_info_update") SESSION_INFO_UPDATE,
    @SerialName("plan") PLAN,
}

@Serializable
enum class AcpRuntimeControl {
    @SerialName("session/set_mode") SESSION_SET_MODE,
    @SerialName("session/set_config_option") SESSION_SET_CONFIG_OPTION,
    @SerialName("session/status") SESSION_STATUS,
}

@Serializable
data class AcpRuntimeHandle(
    val sessionKey: String,
    val backend: String,
    val runtimeSessionName: String,
    val cwd: String? = null,
    val acpxRecordId: String? = null,
    val backendSessionId: String? = null,
    val agentSessionId: String? = null,
)

@Serializable
data class AcpRuntimeEnsureInput(
    val sessionKey: String,
    val agent: String,
    val mode: AcpRuntimeSessionMode,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
)

@Serializable
data class AcpRuntimeTurnInput(
    val handle: AcpRuntimeHandle,
    val text: String,
    val mode: AcpRuntimePromptMode,
    val requestId: String,
)

@Serializable
data class AcpRuntimeCapabilities(
    val controls: List<AcpRuntimeControl>,
    val configOptionKeys: List<String>? = null,
)

@Serializable
data class AcpRuntimeStatus(
    val summary: String? = null,
    val acpxRecordId: String? = null,
    val backendSessionId: String? = null,
    val agentSessionId: String? = null,
    val details: Map<String, String>? = null,
)

@Serializable
data class AcpRuntimeDoctorReport(
    val ok: Boolean,
    val code: String? = null,
    val message: String,
    val installCommand: String? = null,
    val details: List<String>? = null,
)

@Serializable
sealed class AcpRuntimeEvent {
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        val text: String,
        val stream: StreamType? = null,
        val tag: String? = null,
    ) : AcpRuntimeEvent()

    @Serializable
    @SerialName("status")
    data class Status(
        val text: String,
        val tag: String? = null,
        val used: Int? = null,
        val size: Int? = null,
    ) : AcpRuntimeEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val text: String,
        val tag: String? = null,
        val toolCallId: String? = null,
        val status: String? = null,
        val title: String? = null,
    ) : AcpRuntimeEvent()

    @Serializable
    @SerialName("done")
    data class Done(
        val stopReason: String? = null,
    ) : AcpRuntimeEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
        val code: String? = null,
        val retryable: Boolean? = null,
    ) : AcpRuntimeEvent()

    @Serializable
    enum class StreamType {
        @SerialName("output") OUTPUT,
        @SerialName("thought") THOUGHT,
    }
}
