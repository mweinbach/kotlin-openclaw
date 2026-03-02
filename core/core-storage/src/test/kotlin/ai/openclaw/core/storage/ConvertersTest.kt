package ai.openclaw.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvertersTest {

    private val converters = Converters()

    // --- List<String> converters ---

    @Test
    fun `string list round trip`() {
        val list = listOf("one", "two", "three")
        val json = converters.fromStringList(list)
        val result = converters.toStringList(json)
        assertEquals(list, result)
    }

    @Test
    fun `null string list returns null`() {
        assertNull(converters.fromStringList(null))
        assertNull(converters.toStringList(null))
    }

    @Test
    fun `empty list round trip`() {
        val list = emptyList<String>()
        val json = converters.fromStringList(list)
        val result = converters.toStringList(json)
        assertEquals(list, result)
    }

    @Test
    fun `string list with special characters round trip`() {
        val list = listOf("hello world", "key=value", "line\nnewline", "\"quoted\"")
        val json = converters.fromStringList(list)
        val result = converters.toStringList(json)
        assertEquals(list, result)
    }

    // --- Map<String, String> converters ---

    @Test
    fun `string map round trip`() {
        val map = mapOf("key1" to "value1", "key2" to "value2")
        val json = converters.fromStringMap(map)
        val result = converters.toStringMap(json)
        assertEquals(map, result)
    }

    @Test
    fun `null string map returns null`() {
        assertNull(converters.fromStringMap(null))
        assertNull(converters.toStringMap(null))
    }

    @Test
    fun `empty map round trip`() {
        val map = emptyMap<String, String>()
        val json = converters.fromStringMap(map)
        val result = converters.toStringMap(json)
        assertEquals(map, result)
    }

    @Test
    fun `string map with special characters round trip`() {
        val map = mapOf(
            "url" to "https://example.com?a=1&b=2",
            "path" to "/usr/local/bin",
            "json" to """{"nested": true}""",
        )
        val json = converters.fromStringMap(map)
        val result = converters.toStringMap(json)
        assertEquals(map, result)
    }

    // --- FloatArray as blob converters ---

    @Test
    fun `float array round trip via blob`() {
        val array = floatArrayOf(1.0f, 2.5f, -3.14f, 0.0f, Float.MAX_VALUE)
        val blob = converters.fromFloatArray(array)!!
        val result = converters.toFloatArray(blob)!!
        assertEquals(array.size, result.size)
        for (i in array.indices) {
            assertEquals(array[i], result[i], 0.0001f)
        }
    }

    @Test
    fun `null float array returns null`() {
        assertNull(converters.fromFloatArray(null))
        assertNull(converters.toFloatArray(null))
    }

    @Test
    fun `empty float array round trip via blob`() {
        val array = floatArrayOf()
        val blob = converters.fromFloatArray(array)!!
        assertTrue(blob.isEmpty())
        val result = converters.toFloatArray(blob)!!
        assertEquals(0, result.size)
    }

    @Test
    fun `blob size is 4 bytes per float`() {
        val array = floatArrayOf(1.0f, 2.0f, 3.0f)
        val blob = converters.fromFloatArray(array)!!
        assertEquals(12, blob.size) // 3 floats * 4 bytes
    }

    @Test
    fun `single float round trip via blob`() {
        val array = floatArrayOf(42.0f)
        val blob = converters.fromFloatArray(array)!!
        val result = converters.toFloatArray(blob)!!
        assertEquals(1, result.size)
        assertEquals(42.0f, result[0])
    }

    // --- Companion helper methods ---

    @Test
    fun `float array to JSON string round trip`() {
        val array = floatArrayOf(1.0f, 2.5f, -3.14f)
        val jsonStr = Converters.floatArrayToJsonString(array)
        val result = Converters.jsonStringToFloatArray(jsonStr)
        assertEquals(array.size, result.size)
        for (i in array.indices) {
            assertEquals(array[i], result[i], 0.001f)
        }
    }

    @Test
    fun `empty float array to JSON string round trip`() {
        val array = floatArrayOf()
        val jsonStr = Converters.floatArrayToJsonString(array)
        val result = Converters.jsonStringToFloatArray(jsonStr)
        assertEquals(0, result.size)
    }

    @Test
    fun `float array negative values round trip via blob`() {
        val array = floatArrayOf(-1.0f, -Float.MAX_VALUE, Float.MIN_VALUE)
        val blob = converters.fromFloatArray(array)!!
        val result = converters.toFloatArray(blob)!!
        assertEquals(array.size, result.size)
        for (i in array.indices) {
            assertEquals(array[i], result[i])
        }
    }
}
