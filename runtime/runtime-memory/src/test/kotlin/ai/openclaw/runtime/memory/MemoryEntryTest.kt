package ai.openclaw.runtime.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemoryEntryTest {

    @Test
    fun `entries with same id are equal`() {
        val a = MemoryEntry(id = "1", content = "hello", source = "test")
        val b = MemoryEntry(id = "1", content = "world", source = "other")
        assertEquals(a, b)
    }

    @Test
    fun `entries with different id are not equal`() {
        val a = MemoryEntry(id = "1", content = "hello", source = "test")
        val b = MemoryEntry(id = "2", content = "hello", source = "test")
        assertNotEquals(a, b)
    }

    @Test
    fun `hashCode is based on id`() {
        val a = MemoryEntry(id = "1", content = "hello", source = "test")
        val b = MemoryEntry(id = "1", content = "world", source = "other")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `default metadata is empty map`() {
        val entry = MemoryEntry(id = "1", content = "hello", source = "test")
        assertTrue(entry.metadata.isEmpty())
    }

    @Test
    fun `default embedding is null`() {
        val entry = MemoryEntry(id = "1", content = "hello", source = "test")
        assertEquals(null, entry.embedding)
    }

    @Test
    fun `createdAt is set automatically`() {
        val before = System.currentTimeMillis()
        val entry = MemoryEntry(id = "1", content = "hello", source = "test")
        val after = System.currentTimeMillis()
        assertTrue(entry.createdAt in before..after)
    }
}
