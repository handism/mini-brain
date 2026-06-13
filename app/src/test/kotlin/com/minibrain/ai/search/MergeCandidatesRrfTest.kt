package com.minibrain.ai.search

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeCandidatesRrfTest {

    private fun citation(docId: Long, heading: String, snippet: String = "", source: SourceType = SourceType.UNKNOWN) =
        Citation(headingPath = heading, snippet = snippet, docId = docId, source = source)

    @Test
    fun `複数ソースに出現する候補が単一ソースの候補より上位になる`() {
        val both = citation(1, "A")
        val onlyVector = citation(2, "B")
        val merged = mergeCandidatesRrf(
            rankLists = listOf(listOf(both), listOf(both, onlyVector)),
            limit = 10,
        )
        assertEquals(2, merged.size)
        assertEquals(1L, merged[0].docId) // 2 ソース合算 > 1 ソース
        assertEquals(2L, merged[1].docId)
    }

    @Test
    fun `同キーは重複排除され最初に出現したCitationが残る`() {
        val metaVersion = citation(1, "A", snippet = "[日付: 2024-05-01] 本文", source = SourceType.METADATA)
        val bm25Version = citation(1, "A", snippet = "本文のみ", source = SourceType.BM25)
        val merged = mergeCandidatesRrf(
            rankLists = listOf(listOf(metaVersion), listOf(bm25Version)),
            limit = 10,
        )
        assertEquals(1, merged.size)
        // meta リストを先に渡したので [日付:] snippet 付きが残る
        assertEquals(SourceType.METADATA, merged[0].source)
        assertTrue(merged[0].snippet.startsWith("[日付:"))
    }

    @Test
    fun `RRFスコアが正しく合算される`() {
        val c = citation(1, "A")
        val merged = mergeCandidatesRrf(
            rankLists = listOf(listOf(c), listOf(c)),
            limit = 10,
            k = 60,
        )
        // rank 0 が 2 ソース: 1/61 + 1/61
        assertEquals(2f / 61f, merged[0].score, 1e-6f)
    }

    @Test
    fun `同一リスト内ではrankが小さいほど上位`() {
        val first = citation(1, "A")
        val second = citation(2, "B")
        val merged = mergeCandidatesRrf(rankLists = listOf(listOf(first, second)), limit = 10)
        assertEquals(1L, merged[0].docId)
        assertEquals(2L, merged[1].docId)
    }

    @Test
    fun `limitで件数が制限される`() {
        val list = (1L..10L).map { citation(it, "H$it") }
        val merged = mergeCandidatesRrf(rankLists = listOf(list), limit = 3)
        assertEquals(3, merged.size)
    }
}
