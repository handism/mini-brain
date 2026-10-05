package com.minibrain.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Bm25ScorerTest {

    /** 'pcnalx' 形式の blob を組み立てる。x は [phrase][col] ごとに (tf, 全体の出現数, df)。 */
    private fun blob(n: Int, avg: IntArray, len: IntArray, x: List<Triple<Int, Int, Int>>): ByteArray {
        val c = avg.size
        val p = x.size / c
        val ints = intArrayOf(p, c, n) + avg + len + x.flatMap { listOf(it.first, it.second, it.third) }
        val buf = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.nativeOrder())
        ints.forEach { buf.putInt(it) }
        return buf.array()
    }

    @Test
    fun `出現数が多いほど高い`() {
        val once = blob(100, intArrayOf(10, 5), intArrayOf(10, 5), listOf(Triple(1, 5, 5), Triple(0, 0, 0)))
        val twice = blob(100, intArrayOf(10, 5), intArrayOf(10, 5), listOf(Triple(2, 5, 5), Triple(0, 0, 0)))
        assertTrue(Bm25Scorer.score(twice) > Bm25Scorer.score(once))
    }

    @Test
    fun `珍しい語ほど高い`() {
        val rare = blob(100, intArrayOf(10, 5), intArrayOf(10, 5), listOf(Triple(1, 1, 1), Triple(0, 0, 0)))
        val common = blob(100, intArrayOf(10, 5), intArrayOf(10, 5), listOf(Triple(1, 90, 90), Triple(0, 0, 0)))
        assertTrue(Bm25Scorer.score(rare) > Bm25Scorer.score(common))
    }

    @Test
    fun `同じ出現数なら短い行ほど高い`() {
        val short = blob(100, intArrayOf(10, 5), intArrayOf(5, 5), listOf(Triple(1, 5, 5), Triple(0, 0, 0)))
        val long = blob(100, intArrayOf(10, 5), intArrayOf(40, 5), listOf(Triple(1, 5, 5), Triple(0, 0, 0)))
        assertTrue(Bm25Scorer.score(short) > Bm25Scorer.score(long))
    }

    @Test
    fun `本文の一致は見出しの一致より重い`() {
        val inText = blob(100, intArrayOf(10, 10), intArrayOf(10, 10), listOf(Triple(1, 5, 5), Triple(0, 0, 0)))
        val inHeading = blob(100, intArrayOf(10, 10), intArrayOf(10, 10), listOf(Triple(0, 0, 0), Triple(1, 5, 5)))
        assertTrue(Bm25Scorer.score(inText) > Bm25Scorer.score(inHeading))
    }

    @Test
    fun `壊れた blob は 0`() {
        assertEquals(0.0, Bm25Scorer.score(ByteArray(0)), 0.0)
        assertEquals(0.0, Bm25Scorer.score(ByteArray(8)), 0.0)
    }

    @Test
    fun `rank はスコア順に limit 件、同点は id 順`() {
        val low = blob(100, intArrayOf(10, 5), intArrayOf(10, 5), listOf(Triple(1, 50, 50), Triple(0, 0, 0)))
        val high = blob(100, intArrayOf(10, 5), intArrayOf(10, 5), listOf(Triple(3, 5, 5), Triple(0, 0, 0)))
        val matches = listOf(FtsMatchInfo(1, low), FtsMatchInfo(2, high), FtsMatchInfo(3, low))
        assertEquals(listOf(2L, 1L), Bm25Scorer.rank(matches, 2))
    }
}
