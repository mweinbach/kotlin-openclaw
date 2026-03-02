package ai.openclaw.core.storage

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room type converters for complex types stored as JSON strings or binary blobs.
 */
class Converters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // --- List<String> ---

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let { json.decodeFromString<List<String>>(it) }
    }

    // --- Map<String, String> ---

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        return value?.let { json.decodeFromString<Map<String, String>>(it) }
    }

    // --- FloatArray as binary blob ---

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in value) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray?): FloatArray? {
        if (value == null) return null
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(value.size / 4)
        for (i in result.indices) {
            result[i] = buffer.getFloat()
        }
        return result
    }

    companion object {
        private val helperJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /**
         * Encode a FloatArray to a JSON string. Useful for the embeddingJson column.
         */
        fun floatArrayToJsonString(value: FloatArray): String {
            return helperJson.encodeToString(value.toList())
        }

        /**
         * Decode a JSON string to a FloatArray. Useful for the embeddingJson column.
         */
        fun jsonStringToFloatArray(value: String): FloatArray {
            return helperJson.decodeFromString<List<Float>>(value).toFloatArray()
        }
    }
}
