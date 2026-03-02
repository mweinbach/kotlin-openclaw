package ai.openclaw.runtime.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CosineSimilarityTest {

    @Test
    fun `identical vectors have similarity 1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0, cosineSimilarity(v, v), 1e-9)
    }

    @Test
    fun `opposite vectors have similarity -1`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        assertEquals(-1.0, cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `orthogonal vectors have similarity 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0, cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `zero vector returns 0`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(0.0, cosineSimilarity(zero, v), 1e-9)
    }

    @Test
    fun `both zero vectors return 0`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        assertEquals(0.0, cosineSimilarity(zero, zero), 1e-9)
    }

    @Test
    fun `scaled vectors have similarity 1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f)
        assertEquals(1.0, cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `mismatched dimensions throw`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertFailsWith<IllegalArgumentException> {
            cosineSimilarity(a, b)
        }
    }

    @Test
    fun `known angle produces expected similarity`() {
        // 45-degree angle: cos(45) ≈ 0.7071
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 1f)
        assertEquals(0.7071, cosineSimilarity(a, b), 1e-3)
    }
}
