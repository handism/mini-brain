package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.data.db.entities.ChunkEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerDocumentExpanderTest {

    private fun chunk(docId: Long, heading: String, text: String) =
        ChunkEntity(docId = docId, headingPath = heading, text = text, embedding = ByteArray(0))

    private val yamamoto = listOf(
        chunk(1, "山本さん.md", "- ソラリスの Android チームリード。自分の上司。"),
        chunk(1, "山本さん.md > 人となり", "- 話をさえぎらずに最後まで聞いてくれる\n- 趣味は登山"),
        chunk(1, "山本さん.md > 気をつけること", "- 結論から話す。経緯の説明が長いと途中で質問が入る"),
    )

    @Test
    fun `短い文書は見出し付きの全文になる`() {
        val text = AnswerDocumentExpander.documentText(yamamoto, anchor = 0, maxChars = 1500)
        assertTrue(text.startsWith("- ソラリスの"))
        assertTrue(text.contains("## 気をつけること\n- 結論から話す"))
        assertFalse(text.contains("…"))
    }

    @Test
    fun `隣のチャンクと重なる部分は 1 回だけにする`() {
        val overlap = "経緯の説明が長いと途中で質問が入る。"
        val chunks = listOf(
            chunk(1, "a.md > 節", "前半の本文です。$overlap"),
            chunk(1, "a.md > 節", "${overlap}後半の本文です。"),
        )
        val text = AnswerDocumentExpander.documentText(chunks, anchor = 0, maxChars = 1500)
        assertEquals(1, Regex(overlap).findAll(text).count())
        assertTrue(text.endsWith("後半の本文です。"))
    }

    @Test
    fun `長い文書は一致したチャンクの前後に広げ、飛ばした所に印を付ける`() {
        val chunks = (0 until 10).map { chunk(1, "long.md > 節$it", "本文$it".padEnd(100, 'x')) }
        val text = AnswerDocumentExpander.documentText(chunks, anchor = 5, maxChars = 350)
        assertTrue(text.contains("本文5"))
        assertTrue(text.contains("本文4") && text.contains("本文6"))
        assertFalse(text.contains("本文0"))
        assertTrue(text.startsWith("…") && text.endsWith("…"))
    }

    @Test
    fun `snippet の書き出しを含むチャンクを基準にする`() {
        assertEquals(2, AnswerDocumentExpander.anchorIndex(yamamoto, "- 結論から話す。経緯の説明が"))
        assertEquals(0, AnswerDocumentExpander.anchorIndex(yamamoto, "どこにも無い文"))
    }

    @Test
    fun `上位 3 文書だけを広げ、同じ文書の 2 件目は外し、日付は残す`() = runTest {
        val cache: SearchRequestCache = mockk()
        coEvery { cache.chunksByDoc() } returns mapOf(
            1L to yamamoto,
            2L to listOf(chunk(2, "b.md", "B の全文")),
            3L to listOf(chunk(3, "c.md", "C の全文")),
            4L to listOf(chunk(4, "d.md", "D の全文")),
        )
        fun cit(docId: Long, snippet: String) =
            Citation(headingPath = "h$docId", snippet = snippet, docId = docId, relativePath = "p$docId.md")
        val out = AnswerDocumentExpander.expand(
            listOf(
                cit(1, "- ソラリスの Android"),
                cit(2, "[日付: 2026-09-01]\nB"),
                cit(1, "- 結論から話す"),
                cit(3, "C"),
                cit(4, "D の一部"),
            ),
            cache,
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), out.map { it.docId })
        assertTrue(out[0].snippet.contains("結論から話す"))
        assertEquals(AnswerDocumentExpander.FULL_TEXT_HEADING, out[0].headingPath)
        assertTrue(out[1].snippet.startsWith("[日付: 2026-09-01]") && out[1].snippet.contains("B の全文"))
        assertEquals("D の一部", out[3].snippet)
    }
}
