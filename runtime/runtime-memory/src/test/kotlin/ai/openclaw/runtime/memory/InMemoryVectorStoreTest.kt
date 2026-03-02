package ai.openclaw.runtime.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryVectorStoreTest {

    private fun makeEntry(
        id: String,
        content: String = "content-$id",
        embedding: FloatArray? = null,
    ) = MemoryEntry(id = id, content = content, source = "test", embedding = embedding)

    @Test
    fun `add and size`() {
        val store = InMemoryVectorStore()
        assertEquals(0, store.size())

        store.add(makeEntry("1", embedding = floatArrayOf(1f, 0f, 0f)))
        assertEquals(1, store.size())

        store.add(makeEntry("2", embedding = floatArrayOf(0f, 1f, 0f)))
        assertEquals(2, store.size())
    }

    @Test
    fun `add replaces entry with same id`() {
        val store = InMemoryVectorStore()
        store.add(makeEntry("1", content = "old", embedding = floatArrayOf(1f, 0f)))
        store.add(makeEntry("1", content = "new", embedding = floatArrayOf(0f, 1f)))

        assertEquals(1, store.size())
        val results = store.search(floatArrayOf(0f, 1f), topK = 1)
        assertEquals("new", results.first().entry.content)
    }

    @Test
    fun `remove existing entry`() {
        val store = InMemoryVectorStore()
        store.add(makeEntry("1", embedding = floatArrayOf(1f, 0f)))
        store.add(makeEntry("2", embedding = floatArrayOf(0f, 1f)))

        store.remove("1")
        assertEquals(1, store.size())

        val results = store.search(floatArrayOf(1f, 0f), topK = 10)
        assertEquals(1, results.size)
        assertEquals("2", results.first().entry.id)
    }

    @Test
    fun `remove non-existent id is no-op`() {
        val store = InMemoryVectorStore()
        store.add(makeEntry("1", embedding = floatArrayOf(1f, 0f)))
        store.remove("nonexistent")
        assertEquals(1, store.size())
    }

    @Test
    fun `search returns results sorted by similarity`() {
        val store = InMemoryVectorStore()
        val query = floatArrayOf(1f, 0f, 0f)

        // Exact match
        store.add(makeEntry("exact", embedding = floatArrayOf(1f, 0f, 0f)))
        // Partial match
        store.add(makeEntry("partial", embedding = floatArrayOf(1f, 1f, 0f)))
        // Orthogonal
        store.add(makeEntry("orthogonal", embedding = floatArrayOf(0f, 0f, 1f)))

        val results = store.search(query, topK = 10)
        assertEquals(3, results.size)
        assertEquals("exact", results[0].entry.id)
        assertEquals("partial", results[1].entry.id)
        assertEquals("orthogonal", results[2].entry.id)

        assertEquals(1.0, results[0].score, 1e-9)
        assertTrue(results[1].score > 0.5)
        assertEquals(0.0, results[2].score, 1e-9)
    }

    @Test
    fun `search respects topK`() {
        val store = InMemoryVectorStore()
        for (i in 1..10) {
            store.add(makeEntry("$i", embedding = floatArrayOf(i.toFloat(), 1f)))
        }

        val results = store.search(floatArrayOf(10f, 1f), topK = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `search respects minScore`() {
        val store = InMemoryVectorStore()
        val query = floatArrayOf(1f, 0f, 0f)

        store.add(makeEntry("high", embedding = floatArrayOf(1f, 0f, 0f)))
        store.add(makeEntry("low", embedding = floatArrayOf(0f, 0f, 1f)))

        val results = store.search(query, minScore = 0.5)
        assertEquals(1, results.size)
        assertEquals("high", results.first().entry.id)
    }

    @Test
    fun `search skips entries without embeddings`() {
        val store = InMemoryVectorStore()
        store.add(makeEntry("no-embedding"))
        store.add(makeEntry("has-embedding", embedding = floatArrayOf(1f, 0f)))

        val results = store.search(floatArrayOf(1f, 0f))
        assertEquals(1, results.size)
        assertEquals("has-embedding", results.first().entry.id)
    }

    @Test
    fun `search on empty store returns empty list`() {
        val store = InMemoryVectorStore()
        val results = store.search(floatArrayOf(1f, 0f))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchMmr returns empty for empty store`() {
        val store = InMemoryVectorStore()
        val results = store.searchMmr(floatArrayOf(1f, 0f))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchMmr promotes diversity over similar results`() {
        val store = InMemoryVectorStore()
        val query = floatArrayOf(1f, 0f, 0f)

        // Two very similar to query
        store.add(makeEntry("similar1", embedding = floatArrayOf(1f, 0.1f, 0f)))
        store.add(makeEntry("similar2", embedding = floatArrayOf(1f, 0.2f, 0f)))
        // One different but somewhat relevant
        store.add(makeEntry("diverse", embedding = floatArrayOf(0.5f, 0f, 0.866f)))

        // With low lambda (high diversity preference), diverse entry should rank higher
        val results = store.searchMmr(query, topK = 3, lambda = 0.3)
        assertEquals(3, results.size)
        // First should still be the most relevant
        assertEquals("similar1", results[0].entry.id)
        // Second should be the diverse one since it's different from first
        assertEquals("diverse", results[1].entry.id)
    }

    @Test
    fun `searchMmr with lambda 1 behaves like regular search`() {
        val store = InMemoryVectorStore()
        val query = floatArrayOf(1f, 0f, 0f)

        store.add(makeEntry("best", embedding = floatArrayOf(1f, 0f, 0f)))
        store.add(makeEntry("second", embedding = floatArrayOf(0.9f, 0.1f, 0f)))
        store.add(makeEntry("third", embedding = floatArrayOf(0.7f, 0.3f, 0f)))

        val regularResults = store.search(query, topK = 3)
        val mmrResults = store.searchMmr(query, topK = 3, lambda = 1.0)

        assertEquals(regularResults.map { it.entry.id }, mmrResults.map { it.entry.id })
    }

    @Test
    fun `searchMmr respects topK`() {
        val store = InMemoryVectorStore()
        for (i in 1..10) {
            store.add(makeEntry("$i", embedding = floatArrayOf(i.toFloat(), 1f)))
        }

        val results = store.searchMmr(floatArrayOf(10f, 1f), topK = 3)
        assertEquals(3, results.size)
    }
}
