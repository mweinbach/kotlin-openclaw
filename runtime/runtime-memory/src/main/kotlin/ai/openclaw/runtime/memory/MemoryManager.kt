package ai.openclaw.runtime.memory

import java.util.UUID

/**
 * High-level memory manager combining embedding and vector search.
 * Manages storing, embedding, and retrieving memories.
 */
class MemoryManager(
    private val embeddingProvider: EmbeddingProvider? = null,
    private val store: InMemoryVectorStore = InMemoryVectorStore(),
) {
    /**
     * Store a memory entry, optionally computing its embedding.
     */
    suspend fun store(
        content: String,
        source: String,
        metadata: Map<String, String> = emptyMap(),
        id: String = UUID.randomUUID().toString(),
    ): MemoryEntry {
        val embedding = embeddingProvider?.let {
            val results = it.embed(listOf(content))
            results.firstOrNull()
        }

        val entry = MemoryEntry(
            id = id,
            content = content,
            source = source,
            embedding = embedding,
            metadata = metadata,
        )
        store.add(entry)
        return entry
    }

    /**
     * Store multiple memories in a batch, embedding them together for efficiency.
     */
    suspend fun storeBatch(
        items: List<Pair<String, Map<String, String>>>,
        source: String,
    ): List<MemoryEntry> {
        val texts = items.map { it.first }
        val embeddings = embeddingProvider?.embed(texts)

        return items.mapIndexed { index, (content, metadata) ->
            val entry = MemoryEntry(
                id = UUID.randomUUID().toString(),
                content = content,
                source = source,
                embedding = embeddings?.getOrNull(index),
                metadata = metadata,
            )
            store.add(entry)
            entry
        }
    }

    /**
     * Search memories by semantic similarity.
     */
    suspend fun search(
        query: String,
        topK: Int = 5,
        minScore: Double = 0.3,
        useMmr: Boolean = false,
    ): List<MemorySearchResult> {
        val provider = embeddingProvider
            ?: return emptyList()

        val queryEmbedding = provider.embed(listOf(query)).firstOrNull()
            ?: return emptyList()

        return if (useMmr) {
            store.searchMmr(queryEmbedding, topK = topK)
        } else {
            store.search(queryEmbedding, topK = topK, minScore = minScore)
        }
    }

    /**
     * Remove a memory by ID.
     */
    fun remove(id: String) {
        store.remove(id)
    }

    /**
     * Get the number of stored memories.
     */
    fun size(): Int = store.size()
}
