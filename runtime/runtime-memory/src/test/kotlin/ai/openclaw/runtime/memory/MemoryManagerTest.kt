package ai.openclaw.runtime.memory

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mock embedding provider that returns deterministic embeddings for testing.
 * Maps text content to simple vectors based on word hashing.
 */
class MockEmbeddingProvider(private val dimensions: Int = 8) : EmbeddingProvider {
    override val id = "mock"
    var embedCallCount = 0
        private set

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        embedCallCount++
        return texts.map { text ->
            // Generate a deterministic embedding from text content
            val result = FloatArray(dimensions)
            for ((i, char) in text.toCharArray().withIndex()) {
                result[i % dimensions] += char.code.toFloat()
            }
            // Normalize
            val norm = kotlin.math.sqrt(result.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 0f) {
                for (i in result.indices) result[i] /= norm
            }
            result
        }
    }
}

class MemoryManagerTest {

    @Test
    fun `store creates entry with embedding`() = runTest {
        val provider = MockEmbeddingProvider()
        val manager = MemoryManager(embeddingProvider = provider)

        val entry = manager.store("hello world", source = "test")

        assertEquals("hello world", entry.content)
        assertEquals("test", entry.source)
        assertNotNull(entry.embedding)
        assertEquals(1, manager.size())
        assertEquals(1, provider.embedCallCount)
    }

    @Test
    fun `store without provider creates entry without embedding`() = runTest {
        val manager = MemoryManager(embeddingProvider = null)

        val entry = manager.store("hello world", source = "test")

        assertEquals("hello world", entry.content)
        assertNull(entry.embedding)
        assertEquals(1, manager.size())
    }

    @Test
    fun `store with custom id`() = runTest {
        val manager = MemoryManager(embeddingProvider = MockEmbeddingProvider())

        val entry = manager.store("content", source = "test", id = "custom-id")

        assertEquals("custom-id", entry.id)
    }

    @Test
    fun `store with metadata`() = runTest {
        val manager = MemoryManager(embeddingProvider = MockEmbeddingProvider())
        val meta = mapOf("key" to "value", "type" to "note")

        val entry = manager.store("content", source = "test", metadata = meta)

        assertEquals(meta, entry.metadata)
    }

    @Test
    fun `storeBatch embeds all texts in single call`() = runTest {
        val provider = MockEmbeddingProvider()
        val manager = MemoryManager(embeddingProvider = provider)

        val items = listOf(
            "first" to mapOf("idx" to "0"),
            "second" to mapOf("idx" to "1"),
            "third" to mapOf("idx" to "2"),
        )

        val entries = manager.storeBatch(items, source = "batch")

        assertEquals(3, entries.size)
        assertEquals(3, manager.size())
        assertEquals(1, provider.embedCallCount) // single batch call
        entries.forEachIndexed { index, entry ->
            assertEquals("batch", entry.source)
            assertNotNull(entry.embedding)
            assertEquals(items[index].second, entry.metadata)
        }
    }

    @Test
    fun `storeBatch without provider`() = runTest {
        val manager = MemoryManager(embeddingProvider = null)

        val items = listOf(
            "first" to emptyMap<String, String>(),
            "second" to emptyMap(),
        )

        val entries = manager.storeBatch(items, source = "batch")

        assertEquals(2, entries.size)
        entries.forEach { assertNull(it.embedding) }
    }

    @Test
    fun `search returns relevant results`() = runTest {
        val provider = MockEmbeddingProvider()
        val manager = MemoryManager(embeddingProvider = provider)

        manager.store("kotlin programming language", source = "test")
        manager.store("java programming language", source = "test")
        manager.store("completely unrelated content xyz", source = "test")

        val results = manager.search("kotlin programming", topK = 3, minScore = 0.0)

        assertTrue(results.isNotEmpty())
        // The most similar should be the "kotlin programming language" entry
        assertEquals("kotlin programming language", results.first().entry.content)
    }

    @Test
    fun `search without provider returns empty`() = runTest {
        val manager = MemoryManager(embeddingProvider = null)

        manager.store("something", source = "test")
        val results = manager.search("query")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search with mmr`() = runTest {
        val provider = MockEmbeddingProvider()
        val manager = MemoryManager(embeddingProvider = provider)

        manager.store("kotlin language features", source = "test")
        manager.store("kotlin language syntax", source = "test")
        manager.store("database design patterns", source = "test")

        val results = manager.search("kotlin", topK = 3, minScore = 0.0, useMmr = true)

        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `remove decreases size`() = runTest {
        val manager = MemoryManager(embeddingProvider = MockEmbeddingProvider())

        val entry = manager.store("content", source = "test")
        assertEquals(1, manager.size())

        manager.remove(entry.id)
        assertEquals(0, manager.size())
    }

    @Test
    fun `search respects topK`() = runTest {
        val provider = MockEmbeddingProvider()
        val manager = MemoryManager(embeddingProvider = provider)

        for (i in 1..10) {
            manager.store("content number $i", source = "test")
        }

        val results = manager.search("content", topK = 3, minScore = 0.0)
        assertTrue(results.size <= 3)
    }

    @Test
    fun `search respects minScore`() = runTest {
        val provider = MockEmbeddingProvider()
        val manager = MemoryManager(embeddingProvider = provider)

        manager.store("exactly matching content", source = "test")
        manager.store("something completely different xyz abc", source = "test")

        val results = manager.search("exactly matching content", minScore = 0.9)

        // Only the very close match should be returned
        results.forEach { result ->
            assertTrue(result.score >= 0.9, "Score ${result.score} should be >= 0.9")
        }
    }
}
