package ai.openclaw.runtime.devices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validate that each device tool's parametersSchema is structurally valid JSON Schema
 * and contains the expected properties and required fields.
 *
 * Because the tool classes require an Android [Context] and cannot be instantiated in
 * plain JVM unit tests, we validate the canonical schema strings directly. These strings
 * are identical to those declared in the tool source files.
 */
class ToolSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- helpers ----

    private fun parseSchema(schema: String) = json.parseToJsonElement(schema).jsonObject

    private fun assertIsObjectSchema(schema: String) {
        val obj = parseSchema(schema)
        assertEquals("object", obj["type"]?.jsonPrimitive?.content)
        assertNotNull(obj["properties"], "Schema must have 'properties'")
    }

    private fun assertHasProperty(schema: String, propertyName: String) {
        val props = parseSchema(schema)["properties"]!!.jsonObject
        assertNotNull(props[propertyName], "Schema must have property '$propertyName'")
    }

    private fun assertRequiredContains(schema: String, fieldName: String) {
        val required = parseSchema(schema)["required"]
        assertNotNull(required, "Schema must have 'required' array")
        val arr = required.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(arr.contains(fieldName), "required should contain '$fieldName'")
    }

    // ---- CameraTool schema ----

    private val cameraSchema = """
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

    @Test
    fun `CameraTool schema is valid object schema`() {
        assertIsObjectSchema(cameraSchema)
    }

    @Test
    fun `CameraTool schema has action, duration_ms, camera properties`() {
        assertHasProperty(cameraSchema, "action")
        assertHasProperty(cameraSchema, "duration_ms")
        assertHasProperty(cameraSchema, "camera")
    }

    @Test
    fun `CameraTool schema requires action`() {
        assertRequiredContains(cameraSchema, "action")
    }

    @Test
    fun `CameraTool action enum has photo and video`() {
        val actionEnum = parseSchema(cameraSchema)["properties"]!!
            .jsonObject["action"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("photo", "video"), actionEnum)
    }

    @Test
    fun `CameraTool camera enum has back and front`() {
        val cameraEnum = parseSchema(cameraSchema)["properties"]!!
            .jsonObject["camera"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("back", "front"), cameraEnum)
    }

    // ---- LocationTool schema ----

    private val locationSchema = """
        {
          "type": "object",
          "properties": {
            "precision": {
              "type": "string",
              "enum": ["high", "balanced", "low"],
              "description": "Location precision: high (GPS), balanced (WiFi/cell), low (passive). Default: balanced."
            },
            "geocode": {
              "type": "boolean",
              "description": "If true, include reverse-geocoded address. Default: false."
            }
          }
        }
    """.trimIndent()

    @Test
    fun `LocationTool schema is valid object schema`() {
        assertIsObjectSchema(locationSchema)
    }

    @Test
    fun `LocationTool schema has precision and geocode properties`() {
        assertHasProperty(locationSchema, "precision")
        assertHasProperty(locationSchema, "geocode")
    }

    @Test
    fun `LocationTool precision enum has high, balanced, low`() {
        val precisionEnum = parseSchema(locationSchema)["properties"]!!
            .jsonObject["precision"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("high", "balanced", "low"), precisionEnum)
    }

    @Test
    fun `LocationTool geocode type is boolean`() {
        val geocodeType = parseSchema(locationSchema)["properties"]!!
            .jsonObject["geocode"]!!.jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("boolean", geocodeType)
    }

    // ---- NotificationTool schema ----

    private val notificationSchema = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "Notification title."
            },
            "body": {
              "type": "string",
              "description": "Notification body text."
            },
            "priority": {
              "type": "string",
              "enum": ["high", "default", "low"],
              "description": "Notification priority (default: default)."
            },
            "actions": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "label": { "type": "string", "description": "Button label." },
                  "action_id": { "type": "string", "description": "Identifier for this action." }
                },
                "required": ["label", "action_id"]
              },
              "description": "Optional action buttons (max 3)."
            }
          },
          "required": ["title", "body"]
        }
    """.trimIndent()

    @Test
    fun `NotificationTool schema is valid object schema`() {
        assertIsObjectSchema(notificationSchema)
    }

    @Test
    fun `NotificationTool schema requires title and body`() {
        assertRequiredContains(notificationSchema, "title")
        assertRequiredContains(notificationSchema, "body")
    }

    @Test
    fun `NotificationTool schema has all four properties`() {
        assertHasProperty(notificationSchema, "title")
        assertHasProperty(notificationSchema, "body")
        assertHasProperty(notificationSchema, "priority")
        assertHasProperty(notificationSchema, "actions")
    }

    @Test
    fun `NotificationTool actions items have required label and action_id`() {
        val items = parseSchema(notificationSchema)["properties"]!!
            .jsonObject["actions"]!!.jsonObject["items"]!!.jsonObject
        val required = items["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("label"))
        assertTrue(required.contains("action_id"))
    }

    @Test
    fun `NotificationTool priority enum has high, default, low`() {
        val priorityEnum = parseSchema(notificationSchema)["properties"]!!
            .jsonObject["priority"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("high", "default", "low"), priorityEnum)
    }

    // ---- TtsTool schema ----

    private val ttsSchema = """
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

    @Test
    fun `TtsTool schema is valid object schema`() {
        assertIsObjectSchema(ttsSchema)
    }

    @Test
    fun `TtsTool schema requires text`() {
        assertRequiredContains(ttsSchema, "text")
    }

    @Test
    fun `TtsTool schema has text, provider, voice, language`() {
        assertHasProperty(ttsSchema, "text")
        assertHasProperty(ttsSchema, "provider")
        assertHasProperty(ttsSchema, "voice")
        assertHasProperty(ttsSchema, "language")
    }

    @Test
    fun `TtsTool provider enum has local and elevenlabs`() {
        val providerEnum = parseSchema(ttsSchema)["properties"]!!
            .jsonObject["provider"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("local", "elevenlabs"), providerEnum)
    }

    // ---- VoiceTool schema ----

    private val voiceSchema = """
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

    @Test
    fun `VoiceTool schema is valid object schema`() {
        assertIsObjectSchema(voiceSchema)
    }

    @Test
    fun `VoiceTool schema has duration_ms and language`() {
        assertHasProperty(voiceSchema, "duration_ms")
        assertHasProperty(voiceSchema, "language")
    }

    @Test
    fun `VoiceTool duration_ms type is integer`() {
        val durationMsType = parseSchema(voiceSchema)["properties"]!!
            .jsonObject["duration_ms"]!!.jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("integer", durationMsType)
    }

    // ---- ScreenCaptureTool schema ----

    private val screenCaptureSchema = """
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

    @Test
    fun `ScreenCaptureTool schema is valid object schema`() {
        assertIsObjectSchema(screenCaptureSchema)
    }

    @Test
    fun `ScreenCaptureTool schema has format and quality`() {
        assertHasProperty(screenCaptureSchema, "format")
        assertHasProperty(screenCaptureSchema, "quality")
    }

    @Test
    fun `ScreenCaptureTool format enum has png and jpeg`() {
        val formatEnum = parseSchema(screenCaptureSchema)["properties"]!!
            .jsonObject["format"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("png", "jpeg"), formatEnum)
    }

    @Test
    fun `ScreenCaptureTool quality type is integer`() {
        val qualityType = parseSchema(screenCaptureSchema)["properties"]!!
            .jsonObject["quality"]!!.jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("integer", qualityType)
    }

    // ---- Cross-tool schema consistency ----

    @Test
    fun `all tool schemas parse without error`() {
        listOf(cameraSchema, locationSchema, notificationSchema, ttsSchema, voiceSchema, screenCaptureSchema)
            .forEach { schema ->
                val parsed = json.parseToJsonElement(schema)
                assertNotNull(parsed.jsonObject["type"])
            }
    }

    @Test
    fun `all tool schemas are object type`() {
        listOf(cameraSchema, locationSchema, notificationSchema, ttsSchema, voiceSchema, screenCaptureSchema)
            .forEach { schema ->
                assertEquals("object", parseSchema(schema)["type"]?.jsonPrimitive?.content)
            }
    }
}
