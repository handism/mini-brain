package com.minibrain.ai.agent

import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.data.db.entities.DocumentEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlannerHintBuilderTest {

    private fun createDoc(id: Long, fileName: String, relativePath: String): DocumentEntity {
        return DocumentEntity(
            id = id,
            treeUri = "uri",
            fileUri = "uri",
            fileName = fileName,
            relativePath = relativePath,
            lastModified = 0L,
            contentHash = "hash"
        )
    }

    @Test
    fun testNoHints() = runTest {
        val cache = mockk<SearchRequestCache>()
        coEvery { cache.documents() } returns emptyList()

        val result = PlannerHintBuilder.build("random question", null, cache)
        assertNull(result)
    }

    @Test
    fun testDateRangeHint() = runTest {
        val cache = mockk<SearchRequestCache>()
        coEvery { cache.documents() } returns emptyList()

        val dateRange = DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))
        val result = PlannerHintBuilder.build("question", dateRange, cache)

        assertNotNull(result)
        assertTrue(result!!.contains("timeline_search"))
        assertTrue(result.contains("2023-01-01"))
        assertTrue(result.contains("2023-12-31"))
    }

    @Test
    fun testFileMatchHint() = runTest {
        val cache = mockk<SearchRequestCache>()
        coEvery { cache.documents() } returns listOf(
            createDoc(1, "notes.md", "notes.md"),
            createDoc(2, "other.md", "other.md")
        )

        val result = PlannerHintBuilder.build("notes", null, cache)

        assertNotNull(result)
        assertTrue(result!!.contains("notes.md"))
        assertTrue(result.contains("[d=1]"))
    }

    @Test
    fun testDiaryQueryDateHintsFound() = runTest {
        val cache = mockk<SearchRequestCache>()
        coEvery { cache.documents() } returns listOf(
            createDoc(1, "2023-05-15.md", "diary/2023-05-15.md")
        )

        // Using "2023/05/15" to hit DateResolver.isDiaryQuery and return 2023-05-15
        val result = PlannerHintBuilder.build("2023/05/15", null, cache)

        assertNotNull(result)
        assertTrue("Expected string not found. Result: $result", result!!.contains("2023-05-15"))
        assertTrue(result.contains("[d=1]"))
    }

    @Test
    fun testDiaryQueryDateHintsNotFound() = runTest {
        val cache = mockk<SearchRequestCache>()
        coEvery { cache.documents() } returns emptyList()

        val result = PlannerHintBuilder.build("2023/05/15", null, cache)

        assertNotNull(result)
        assertTrue(result!!.contains("*"))
        assertTrue(result.contains("or"))
    }
}
