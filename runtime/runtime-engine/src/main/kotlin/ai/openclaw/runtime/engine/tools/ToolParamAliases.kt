package ai.openclaw.runtime.engine.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object ToolParamAliases {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun parseObject(input: String): JsonObject? {
        if (input.isBlank()) return null
        return runCatching { json.parseToJsonElement(input).jsonObject }.getOrNull()
    }

    fun getString(params: JsonObject, vararg names: String): String? {
        for (name in names) {
            val value = params[name]?.jsonPrimitive?.contentOrNull ?: continue
            return value
        }
        return null
    }

    fun getBoolean(params: JsonObject, vararg names: String): Boolean? {
        for (name in names) {
            val value = params[name]?.jsonPrimitive?.booleanOrNull
            if (value != null) return value
        }
        return null
    }

    fun getLong(params: JsonObject, vararg names: String): Long? {
        for (name in names) {
            val value = params[name]?.jsonPrimitive?.longOrNull
            if (value != null) return value
        }
        return null
    }

    fun getInt(params: JsonObject, vararg names: String): Int? {
        return getLong(params, *names)?.toInt()
    }

    fun getJsonObject(params: JsonObject, vararg names: String): JsonObject? {
        for (name in names) {
            val value = params[name] as? JsonObject
            if (value != null) return value
        }
        return null
    }

    fun jsonError(tool: String, error: String): String {
        return encodeObject(
            mapOf(
                "status" to "error",
                "tool" to tool,
                "error" to error,
            ),
        )
    }

    fun jsonOk(tool: String, payload: Map<String, Any?>): String {
        val full = linkedMapOf<String, Any?>(
            "status" to "ok",
            "tool" to tool,
        )
        full.putAll(payload)
        return encodeObject(full)
    }

    fun encodeObject(payload: Map<String, Any?>): String {
        val normalized = payload.mapValues { (_, value) -> value.toJsonElement() }
        return json.encodeToString(JsonObject.serializer(), JsonObject(normalized))
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this.toDouble())
        is Map<*, *> -> JsonObject(
            this.entries
                .filter { it.key is String }
                .associate { (key, value) -> key as String to value.toJsonElement() },
        )
        is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(this.toString())
    }
}
