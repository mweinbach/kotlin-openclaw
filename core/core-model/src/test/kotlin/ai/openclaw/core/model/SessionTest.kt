package ai.openclaw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `serialize and deserialize SessionEntry`() {
        val entry = SessionEntry(
            sessionId = "test-session-id",
            updatedAt = 1700000000000L,
            chatType = ChatType.DIRECT,
            model = "anthropic/claude-3-opus",
            modelProvider = "anthropic",
            totalTokens = 5000,
            totalTokensFresh = true,
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<SessionEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun `serialize SessionEntry with all queue fields`() {
        val entry = SessionEntry(
            sessionId = "queue-test",
            updatedAt = 1700000000000L,
            queueMode = QueueMode.STEER,
            queueDebounceMs = 500L,
            queueCap = 10,
            queueDrop = QueueDrop.OLD,
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<SessionEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun `serialize SessionAcpMeta`() {
        val meta = SessionAcpMeta(
            backend = "acpx",
            agent = "default",
            runtimeSessionName = "session-1",
            mode = AcpRuntimeSessionMode.PERSISTENT,
            state = AcpSessionState.IDLE,
            lastActivityAt = 1700000000000L,
        )
        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<SessionAcpMeta>(encoded)
        assertEquals(meta, decoded)
    }

    @Test
    fun `serialize OpenClawConfig`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(
                list = listOf(
                    AgentConfig(
                        id = "default",
                        default = true,
                        name = "TestAgent",
                        model = AgentModelConfig(primary = "anthropic/claude-3-opus"),
                    )
                )
            ),
            session = SessionConfig(
                scope = SessionScope.PER_SENDER,
                idleMinutes = 60,
            ),
        )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<OpenClawConfig>(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `serialize OpenClawConfig with expanded fields`() {
        val config = OpenClawConfig(
            meta = ConfigMetadata(lastTouchedVersion = "1.0.0"),
            browser = BrowserConfig(enabled = true, headless = false),
            approvals = ApprovalsConfig(
                exec = ExecApprovalForwardingConfig(enabled = true, mode = ExecApprovalForwardingMode.SESSION),
            ),
            tools = ExpandedToolsConfig(
                profile = ToolProfileId.FULL,
                allow = listOf("exec", "read"),
                exec = ExecToolConfig(host = "sandbox"),
            ),
            messages = ExpandedMessagesConfig(
                responsePrefix = "[bot]",
                tts = TtsConfig(provider = TtsProvider.ELEVENLABS),
            ),
            memory = ExpandedMemoryConfig(
                backend = MemoryBackend.BUILTIN,
                citations = MemoryCitationsMode.AUTO,
            ),
            channels = ChannelsConfig(
                whatsapp = WhatsAppConfig(enabled = true),
                msteams = MSTeamsConfig(enabled = false),
            ),
        )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<OpenClawConfig>(encoded)
        assertEquals(config, decoded)
    }
}
