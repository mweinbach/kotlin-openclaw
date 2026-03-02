package ai.openclaw.runtime.memory

/**
 * A memory entry with text content and embedding vector.
 */
data class MemoryEntry(
    val id: String,
    val content: String,
    val source: String,
    val embedding: FloatArray? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Search result with relevance score.
 */
data class MemorySearchResult(
    val entry: MemoryEntry,
    val score: Double,
)

/**
 * Interface for embedding providers.
 */
interface EmbeddingProvider {
    val id: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * Cosine similarity between two vectors.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "Vectors must have same dimension" }
    var dotProduct = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dotProduct += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = Math.sqrt(normA) * Math.sqrt(normB)
    return if (denom == 0.0) 0.0 else dotProduct / denom
}

/**
 * In-memory vector store for on-device memory search.
 */
class InMemoryVectorStore {
    private val entries = mutableListOf<MemoryEntry>()

    @Synchronized
    fun add(entry: MemoryEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
    }

    @Synchronized
    fun remove(id: String) {
        entries.removeAll { it.id == id }
    }

    @Synchronized
    fun size(): Int = entries.size

    /**
     * Search using brute-force cosine similarity.
     */
    @Synchronized
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 10,
        minScore: Double = 0.0,
    ): List<MemorySearchResult> {
        return entries
            .filter { it.embedding != null }
            .map { entry ->
                MemorySearchResult(
                    entry = entry,
                    score = cosineSimilarity(queryEmbedding, entry.embedding!!),
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * MMR (Maximal Marginal Relevance) search for diversity.
     */
    @Synchronized
    fun searchMmr(
        queryEmbedding: FloatArray,
        topK: Int = 10,
        lambda: Double = 0.7,
        candidateMultiplier: Int = 4,
    ): List<MemorySearchResult> {
        val candidates = search(queryEmbedding, topK = topK * candidateMultiplier)
        if (candidates.isEmpty()) return emptyList()

        val selected = mutableListOf<MemorySearchResult>()
        val remaining = candidates.toMutableList()

        while (selected.size < topK && remaining.isNotEmpty()) {
            val best = remaining.maxByOrNull { candidate ->
                val relevance = candidate.score
                val maxSimilarity = if (selected.isEmpty()) 0.0 else {
                    selected.maxOf { sel ->
                        if (candidate.entry.embedding != null && sel.entry.embedding != null) {
                            cosineSimilarity(candidate.entry.embedding, sel.entry.embedding)
                        } else 0.0
                    }
                }
                lambda * relevance - (1 - lambda) * maxSimilarity
            } ?: break

            selected.add(best)
            remaining.remove(best)
        }

        return selected
    }
}
