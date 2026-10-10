package com.minibrain.ai.agent

import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.data.db.entities.DocumentEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlannerHintBuilderTest {

    private val mockCache = mockk<SearchRequestCache>()

    @Test
    fun `build returns null when no hints are generated`() = runBlocking {
        coEvery { mockCache.documents() } returns emptyList()

        val hint = PlannerHintBuilder.build(
            question = "What is the meaning of life?",
            dateRange = null,
            cache = mockCache
        )

        assertNull(hint)
    }

    @Test
    fun `build adds date range hint`() = runBlocking {
        coEvery { mockCache.documents() } returns emptyList()

        val dateRange = DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))
        val hint = PlannerHintBuilder.build(
            question = "What happened last year?",
            dateRange = dateRange,
            cache = mockCache
        )

        assertTrue(hint!!.contains("期間クエリ検出: 2023-01-01 〜 2023-12-31 / timeline_search を推奨"))
    }

    @Test
    fun `build adds file match hint`() = runBlocking {
        val doc1 = DocumentEntity(id = 1, treeUri = "tree1", fileUri = "uri1", fileName = "meeting_notes.md", relativePath = "meeting_notes.md", lastModified = 0L, contentHash = "hash1")
        coEvery { mockCache.documents() } returns listOf(doc1)

        val hint = PlannerHintBuilder.build(
            question = "Show me the meeting_notes",
            dateRange = null,
            cache = mockCache
        )

        assertTrue(hint!!.contains("質問にマッチするファイル候補: [d=1] meeting_notes.md"))
    }

    @Test
    fun `build adds date hints for diary query`() = runBlocking {
        val currentYear = LocalDate.now().year
        val doc1 = DocumentEntity(id = 1, treeUri = "tree1", fileUri = "uri1", fileName = "15.md", relativePath = "$currentYear/05/15.md", lastModified = 0L, contentHash = "hash1")
        coEvery { mockCache.documents() } returns listOf(doc1)

        val hint = PlannerHintBuilder.build(
            question = "${currentYear}年5月15日の日記",
            dateRange = null,
            cache = mockCache
        )

        assertTrue(hint!!.contains("検出された日付: $currentYear-05-15"))
        assertTrue(hint.contains("日付に一致するファイル: [d=1] $currentYear/05/15.md"))
    }


    @Test
    fun `build adds glob recommendations when date files are not found`() = runBlocking {
        coEvery { mockCache.documents() } returns emptyList()
        val currentYear = LocalDate.now().year

        val hint = PlannerHintBuilder.build(
            question = "${currentYear}年5月16日の日記",
            dateRange = null,
            cache = mockCache
        )

        assertTrue(hint!!.contains("検出された日付: $currentYear-05-16"))
        assertTrue(hint.contains("推奨 glob パターン: \"${currentYear}0516*\" or \"$currentYear/05/16*\" or \"$currentYear-05-16*\""))
    }


    @Test
    fun `build combines multiple hints with slash`() = runBlocking {
        val doc1 = DocumentEntity(id = 1, treeUri = "tree1", fileUri = "uri1", fileName = "meeting_notes.md", relativePath = "meeting_notes.md", lastModified = 0L, contentHash = "hash1")
        coEvery { mockCache.documents() } returns listOf(doc1)

        val dateRange = DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))
        val hint = PlannerHintBuilder.build(
            question = "Show me the meeting_notes",
            dateRange = dateRange,
            cache = mockCache
        )

        assertEquals("期間クエリ検出: 2023-01-01 〜 2023-12-31 / timeline_search を推奨 / 質問にマッチするファイル候補: [d=1] meeting_notes.md", hint)
    }
}
