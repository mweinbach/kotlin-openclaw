package ai.openclaw.runtime.gateway

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookManagerTest {

    @Test
    fun `renderTemplate resolves simple fields`() {
        val payload = buildJsonObject {
            put("name", "Alice")
            put("age", 30)
        }
        val result = WebhookManager.renderTemplate("Hello {{name}}, age {{age}}", payload)
        assertEquals("Hello Alice, age 30", result)
    }

    @Test
    fun `renderTemplate resolves nested fields`() {
        val payload = buildJsonObject {
            putJsonObject("user") {
                put("name", "Bob")
                putJsonObject("address") {
                    put("city", "NYC")
                }
            }
        }
        val result = WebhookManager.renderTemplate("{{user.name}} in {{user.address.city}}", payload)
        assertEquals("Bob in NYC", result)
    }

    @Test
    fun `renderTemplate resolves array indices`() {
        val payload = buildJsonObject {
            putJsonArray("items") {
                addJsonObject { put("label", "first") }
                addJsonObject { put("label", "second") }
            }
        }
        val result = WebhookManager.renderTemplate("{{items[0].label}} and {{items[1].label}}", payload)
        assertEquals("first and second", result)
    }

    @Test
    fun `renderTemplate replaces missing fields with empty string`() {
        val payload = buildJsonObject { put("a", "1") }
        val result = WebhookManager.renderTemplate("{{a}} {{missing}}", payload)
        assertEquals("1 ", result)
    }

    @Test
    fun `renderTemplate blocks prototype traversal`() {
        val payload = buildJsonObject {
            put("__proto__", "evil")
            putJsonObject("safe") {
                put("__proto__", "also evil")
            }
        }
        val result1 = WebhookManager.renderTemplate("{{__proto__}}", payload)
        assertEquals("", result1)
        val result2 = WebhookManager.renderTemplate("{{safe.__proto__}}", payload)
        assertEquals("", result2)
    }

    @Test
    fun `renderTemplate handles empty template`() {
        val payload = buildJsonObject { put("a", "1") }
        assertEquals("", WebhookManager.renderTemplate("", payload))
    }

    @Test
    fun `renderTemplate handles template with no placeholders`() {
        val payload = buildJsonObject { put("a", "1") }
        assertEquals("plain text", WebhookManager.renderTemplate("plain text", payload))
    }

    @Test
    fun `renderTemplate handles whitespace in placeholders`() {
        val payload = buildJsonObject { put("name", "Alice") }
        val result = WebhookManager.renderTemplate("{{  name  }}", payload)
        assertEquals("Alice", result)
    }
}
