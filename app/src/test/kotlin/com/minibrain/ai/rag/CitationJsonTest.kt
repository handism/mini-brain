package com.minibrain.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json は Android 実装のため Robolectric 上で動かす
@RunWith(RobolectricTestRunner::class)
class CitationJsonTest {

    @Test
    fun `encode and decode round trip`() {
        val citations = listOf(
            Citation("見出し", "本文", 0.5f, docId = 3L, relativePath = "a/b.md", source = SourceType.METADATA, topicMatch = true),
            Citation("フォルダ: a", "（フォルダ）", 0.1f, source = SourceType.FOLDER),
        )

        assertEquals(citations, CitationJson.decode(CitationJson.encode(citations)))
    }

    @Test
    fun `decode tolerates broken json and unknown source`() {
        assertTrue(CitationJson.decode("not json").isEmpty())
        val decoded = CitationJson.decode("""[{"headingPath":"h","snippet":"s","source":"REMOVED"}]""")
        assertEquals(SourceType.UNKNOWN, decoded.single().source)
    }
}
