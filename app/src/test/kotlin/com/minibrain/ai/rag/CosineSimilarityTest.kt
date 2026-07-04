package com.minibrain.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CosineSimilarityTest {

    @Test
    fun compute_sameSizeVectors_returnsDotProduct() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(4.0f, 5.0f, 6.0f)
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        val result = CosineSimilarity.compute(a, b)
        assertEquals(32.0f, result, 0.0001f)
    }

    @Test
    fun compute_differentSizeVectors_throwsIllegalArgumentException() {
        val a = floatArrayOf(1.0f, 2.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            CosineSimilarity.compute(a, b)
        }
        assertEquals("Vectors must be the same size", exception.message)
    }

    @Test
    fun compute_zeroVectors_returnsZero() {
        val a = floatArrayOf(0.0f, 0.0f)
        val b = floatArrayOf(0.0f, 0.0f)
        val result = CosineSimilarity.compute(a, b)
        assertEquals(0.0f, result, 0.0001f)
    }

    @Test
    fun compute_orthogonalVectors_returnsZero() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f)
        val result = CosineSimilarity.compute(a, b)
        assertEquals(0.0f, result, 0.0001f)
    }

    @Test
    fun compute_identicalVectors_returnsSumOfSquares() {
        val a = floatArrayOf(2.0f, 3.0f)
        val b = floatArrayOf(2.0f, 3.0f)
        // 2*2 + 3*3 = 4 + 9 = 13
        val result = CosineSimilarity.compute(a, b)
        assertEquals(13.0f, result, 0.0001f)
    }

    @Test
    fun topK_returnsTopK_inDescendingOrder() {
        val query = floatArrayOf(1.0f, 1.0f)

        // Compute dot products:
        // doc1: (1*1) + (1*0) = 1.0
        // doc2: (1*2) + (1*2) = 4.0
        // doc3: (1*0.5) + (1*0.5) = 1.0
        // doc4: (1*3) + (1*1) = 4.0
        // doc5: (1*5) + (1*5) = 10.0
        val candidates = listOf(
            Pair(floatArrayOf(1.0f, 0.0f), "doc1"),
            Pair(floatArrayOf(2.0f, 2.0f), "doc2"),
            Pair(floatArrayOf(0.5f, 0.5f), "doc3"),
            Pair(floatArrayOf(3.0f, 1.0f), "doc4"),
            Pair(floatArrayOf(5.0f, 5.0f), "doc5")
        )

        val result = CosineSimilarity.topK(query, candidates, 3)

        assertEquals(3, result.size)
        // Check order and scores (descending order)
        assertEquals(10.0f, result[0].first, 0.0001f)
        assertEquals("doc5", result[0].second)

        // Both doc2 and doc4 have score 4.0. The priority queue behavior with ties
        // might mean order isn't strictly guaranteed between ties, but they should be next.
        assertEquals(4.0f, result[1].first, 0.0001f)
        assertEquals(4.0f, result[2].first, 0.0001f)
    }

    @Test
    fun topK_emptyCandidates_returnsEmptyList() {
        val query = floatArrayOf(1.0f, 1.0f)
        val candidates = emptyList<Pair<FloatArray, Any>>()

        val result = CosineSimilarity.topK(query, candidates, 3)

        assertEquals(0, result.size)
    }

    @Test
    fun topK_kZeroOrNegative_returnsEmptyList() {
        val query = floatArrayOf(1.0f, 1.0f)
        val candidates = listOf(
            Pair(floatArrayOf(1.0f, 1.0f), "doc1")
        )

        var result = CosineSimilarity.topK(query, candidates, 0)
        assertEquals(0, result.size)

        result = CosineSimilarity.topK(query, candidates, -1)
        assertEquals(0, result.size)
    }

    @Test
    fun topK_kGreaterThanCandidates_returnsAllCandidatesSorted() {
        val query = floatArrayOf(1.0f, 1.0f)
        // doc1: 2.0, doc2: 4.0
        val candidates = listOf(
            Pair(floatArrayOf(1.0f, 1.0f), "doc1"),
            Pair(floatArrayOf(2.0f, 2.0f), "doc2")
        )

        val result = CosineSimilarity.topK(query, candidates, 5)

        assertEquals(2, result.size)
        assertEquals(4.0f, result[0].first, 0.0001f)
        assertEquals("doc2", result[0].second)
        assertEquals(2.0f, result[1].first, 0.0001f)
        assertEquals("doc1", result[1].second)
    }
}
