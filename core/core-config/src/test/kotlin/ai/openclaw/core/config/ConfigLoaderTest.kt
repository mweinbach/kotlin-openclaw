package ai.openclaw.core.config

import ai.openclaw.core.model.SessionScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigLoaderTest {
    private val loader = ConfigLoader()

    @Test
    fun `parse minimal config`() {
        val config = loader.parse("""{}""")
        assertNotNull(config)
    }

    @Test
    fun `parse config with agents`() {
        val config = loader.parse("""
        {
            "agents": {
                "list": [
                    { "id": "default", "default": true, "name": "Test" }
                ]
            },
            "session": { "scope": "per-sender" }
        }
        """.trimIndent())
        assertEquals("default", config.agents?.list?.first()?.id)
        assertEquals(SessionScope.PER_SENDER, config.session?.scope)
    }

    @Test
    fun `substitute env vars`() {
        val template = "{\"apiKey\": \"\${MY_KEY}\"}"
        val result = loader.substituteEnvVars(
            template,
            mapOf("MY_KEY" to "secret-123"),
        )
        assertEquals("{\"apiKey\": \"secret-123\"}", result)
    }

    @Test
    fun `unmatched env vars are preserved`() {
        val input = "{\"key\": \"\${MISSING_VAR}\"}"
        val result = loader.substituteEnvVars(input, emptyMap())
        assertEquals(input, result)
    }

    @Test
    fun `merge configs`() {
        val base = loader.parse("""{"session": {"scope": "global"}}""")
        val overlay = loader.parse("""{"session": {"scope": "per-sender"}}""")
        val merged = loader.merge(base, overlay)
        assertEquals(SessionScope.PER_SENDER, merged.session?.scope)
    }

    @Test
    fun `parse session transcript repair config`() {
        val config = loader.parse(
            """
            {
              "session": {
                "transcriptRepair": {
                  "fileRepairEnabled": true,
                  "toolCallInputRepairEnabled": true,
                  "toolResultPairRepairEnabled": true,
                  "allowSyntheticToolResults": false
                }
              }
            }
            """.trimIndent(),
        )
        val transcriptRepair = config.session?.transcriptRepair
        assertNotNull(transcriptRepair)
        assertEquals(true, transcriptRepair.fileRepairEnabled)
        assertEquals(true, transcriptRepair.toolCallInputRepairEnabled)
        assertEquals(true, transcriptRepair.toolResultPairRepairEnabled)
        assertEquals(false, transcriptRepair.allowSyntheticToolResults)
    }
}
