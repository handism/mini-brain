package com.minibrain.data.md

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkEmbeddingTextTest {

    @Test
    fun `パスと見出しを本文の前に付け、拡張子を落とす`() {
        val chunk = Chunk("food/スパイス堂.md > 訪問記録 > 2回目", "ビーフカレーが最高だった")
        assertEquals("food/スパイス堂 > 訪問記録 > 2回目\nビーフカレーが最高だった", chunk.embeddingText())
    }

    @Test
    fun `見出しの無いチャンクはパスだけ付く`() {
        assertEquals("journal/2026-09\n本文", Chunk("journal/2026-09.MD", "本文").embeddingText())
    }

    @Test
    fun `見出し中の md は落とさない`() {
        val chunk = Chunk("tech/memo.md > README.md の書き方", "本文")
        assertEquals("tech/memo > README.md の書き方\n本文", chunk.embeddingText())
    }

    @Test
    fun `MarkdownChunker の headingPath がそのまま使われる`() {
        val chunks = MarkdownChunker.chunk("# 概要\n本文です", "notes/a.md")
        assertEquals("notes/a > 概要\n本文です", chunks.single().embeddingText())
    }
}
