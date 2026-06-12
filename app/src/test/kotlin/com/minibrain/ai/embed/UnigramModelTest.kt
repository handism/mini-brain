package com.minibrain.ai.embed

import org.junit.Assert.assertEquals
import org.junit.Test

class UnigramModelTest {

    private fun model(vararg entries: Pair<String, Double>, unkId: Int = 0): UnigramModel {
        val pieceIds = HashMap<String, Int>()
        val scores = DoubleArray(entries.size)
        entries.forEachIndexed { i, (piece, score) ->
            pieceIds[piece] = i
            scores[i] = score
        }
        return UnigramModel(pieceIds, scores, unkId)
    }

    @Test
    fun `スコア最大のパスを選択する`() {
        // "abc" を [ab][c] (合計 -3.0) と [a][bc] (合計 -4.0) で分割できる場合、前者を選ぶ
        val m = model(
            "<unk>" to 0.0,
            "a" to -2.0,
            "b" to -2.0,
            "c" to -1.0,
            "ab" to -2.0,
            "bc" to -2.0,
        )
        assertEquals(listOf(4, 3), m.tokenize("abc"))
    }

    @Test
    fun `長い piece が有利ならまとめて切る`() {
        val m = model(
            "<unk>" to 0.0,
            "▁" to -1.0,
            "東" to -3.0,
            "京" to -3.0,
            "▁東京" to -4.0,
        )
        assertEquals(listOf(4), m.tokenize("▁東京"))
    }

    @Test
    fun `vocab にない文字は unk になり隣接 unk は融合される`() {
        val m = model(
            "<unk>" to 0.0,
            "a" to -1.0,
        )
        // "a??a" → [a][unk(?)][unk(?)][a] → fuse → [a][unk][a]
        assertEquals(listOf(1, 0, 1), m.tokenize("a☃☄a"))
    }

    @Test
    fun `サロゲートペアの未知文字も 1 文字として unk 化される`() {
        val m = model(
            "<unk>" to 0.0,
            "a" to -1.0,
        )
        // 絵文字 (U+1F600) はサロゲートペア
        assertEquals(listOf(1, 0, 1), m.tokenize("a😀a"))
    }

    @Test
    fun `空文字列は空リスト`() {
        val m = model("<unk>" to 0.0, "a" to -1.0)
        assertEquals(emptyList<Int>(), m.tokenize(""))
    }
}
