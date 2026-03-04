package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolRegistrySchemaNormalizationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class StaticTool(
        override val name: String,
        override val parametersSchema: String,
        override val description: String = "test tool",
    ) : AgentTool {
        override suspend fun execute(input: String, context: ToolContext): String = "{}"
    }

    @Test
    fun `registry flattens top-level union schemas into object schema`() = runTest {
        val registry = ToolRegistry()
        registry.register(
            StaticTool(
                name = "read",
                parametersSchema =
                    """
                    {
                      "anyOf": [
                        {
                          "type": "object",
                          "properties": {
                            "path": {"type": "string"},
                            "mode": {"enum": ["r"]}
                          },
                          "required": ["path"]
                        },
                        {
                          "type": "object",
                          "properties": {
                            "path": {"type": "string"},
                            "mode": {"enum": ["rw"]}
                          },
                          "required": ["path"]
                        }
                      ]
                    }
                    """.trimIndent(),
            ),
        )

        val definition = registry.toDefinitions().single()
        val schema = json.parseToJsonElement(definition.parameters).jsonObject

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)
        assertNotNull(properties["path"])

        val modeEnum = properties["mode"]
            ?.jsonObject
            ?.get("enum")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertEquals(listOf("r", "rw"), modeEnum)
    }

    @Test
    fun `registry enforces object type when properties exist without top-level type`() = runTest {
        val registry = ToolRegistry()
        registry.register(
            StaticTool(
                name = "write",
                parametersSchema =
                    """
                    {
                      "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"}
                      },
                      "required": ["path", "content"]
                    }
                    """.trimIndent(),
            ),
        )

        val definition = registry.toDefinitions().single()
        val schema = json.parseToJsonElement(definition.parameters).jsonObject

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertTrue(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("path"))
        assertTrue(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("content"))
    }
}
