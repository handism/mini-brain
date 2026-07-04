package com.minibrain.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RrfFuserTest {

    private data class DummyItem(val id: String, val content: String = "")

    @Test
    fun `fuse with single list calculates basic RRF scores correctly`() {
        val list = listOf(
            DummyItem("A"),
            DummyItem("B"),
            DummyItem("C")
        )

        val fused = RrfFuser.fuse(
            rankLists = listOf(list),
            keyOf = { it.id },
            k = 60
        )

        assertEquals(3, fused.size)

        // rank 0 (A) -> score: 1 / (60 + 0 + 1) = 1/61
        val itemA = fused.find { it.key == "A" }!!
        assertEquals(1f / 61f, itemA.score, 1e-6f)

        // rank 1 (B) -> score: 1 / (60 + 1 + 1) = 1/62
        val itemB = fused.find { it.key == "B" }!!
        assertEquals(1f / 62f, itemB.score, 1e-6f)

        // rank 2 (C) -> score: 1 / (60 + 2 + 1) = 1/63
        val itemC = fused.find { it.key == "C" }!!
        assertEquals(1f / 63f, itemC.score, 1e-6f)
    }

    @Test
    fun `fuse overlapping items sums scores from multiple lists`() {
        val list1 = listOf(DummyItem("A"), DummyItem("B"))
        val list2 = listOf(DummyItem("B"), DummyItem("C"), DummyItem("A"))

        val fused = RrfFuser.fuse(
            rankLists = listOf(list1, list2),
            keyOf = { it.id },
            k = 60
        )

        assertEquals(3, fused.size)

        // A is at rank 0 in list1, rank 2 in list2
        // score = 1/(61) + 1/(63)
        val expectedScoreA = (1f / 61f) + (1f / 63f)
        val itemA = fused.find { it.key == "A" }!!
        assertEquals(expectedScoreA, itemA.score, 1e-6f)

        // B is at rank 1 in list1, rank 0 in list2
        // score = 1/(62) + 1/(61)
        val expectedScoreB = (1f / 62f) + (1f / 61f)
        val itemB = fused.find { it.key == "B" }!!
        assertEquals(expectedScoreB, itemB.score, 1e-6f)

        // C is at rank 1 in list2
        // score = 1/(62)
        val expectedScoreC = 1f / 62f
        val itemC = fused.find { it.key == "C" }!!
        assertEquals(expectedScoreC, itemC.score, 1e-6f)
    }

    @Test
    fun `fuse respects custom weights array`() {
        val list1 = listOf(DummyItem("A"))
        val list2 = listOf(DummyItem("B"), DummyItem("A"))

        val fused = RrfFuser.fuse(
            rankLists = listOf(list1, list2),
            keyOf = { it.id },
            k = 60,
            weights = listOf(2.0f, 0.5f)
        )

        // A in list1 (rank 0, weight 2.0) -> 2.0 * 1/61
        // A in list2 (rank 1, weight 0.5) -> 0.5 * 1/62
        val expectedScoreA = (2.0f * (1f / 61f)) + (0.5f * (1f / 62f))
        val itemA = fused.find { it.key == "A" }!!
        assertEquals(expectedScoreA, itemA.score, 1e-6f)

        // B in list2 (rank 0, weight 0.5) -> 0.5 * 1/61
        val expectedScoreB = 0.5f * (1f / 61f)
        val itemB = fused.find { it.key == "B" }!!
        assertEquals(expectedScoreB, itemB.score, 1e-6f)
    }

    @Test
    fun `fuse retains first seen item (first-wins) when keys collide`() {
        // Two items with the same key "A" but different content
        val itemA1 = DummyItem("A", "First")
        val itemA2 = DummyItem("A", "Second")

        val fused = RrfFuser.fuse(
            rankLists = listOf(listOf(itemA1), listOf(itemA2)),
            keyOf = { it.id }
        )

        assertEquals(1, fused.size)
        val resultA = fused.first()

        assertEquals("A", resultA.key)
        // Should retain the first seen item
        assertEquals("First", resultA.item.content)
    }

    @Test
    fun `fuse throws IllegalArgumentException if weights size mismatches rankLists size`() {
        val list1 = listOf(DummyItem("A"))

        assertThrows(IllegalArgumentException::class.java) {
            RrfFuser.fuse(
                rankLists = listOf(list1),
                keyOf = { it.id },
                weights = listOf(1.0f, 2.0f) // Two weights for one list
            )
        }
    }

    @Test
    fun `fuse uses custom k value properly`() {
        val list = listOf(DummyItem("A"))

        val fused = RrfFuser.fuse(
            rankLists = listOf(list),
            keyOf = { it.id },
            k = 10 // custom k
        )

        // rank 0 -> 1 / (10 + 0 + 1) = 1/11
        val itemA = fused.find { it.key == "A" }!!
        assertEquals(1f / 11f, itemA.score, 1e-6f)
    }

    @Test
    fun `fuse handles empty lists correctly`() {
        val fused = RrfFuser.fuse(
            rankLists = listOf(emptyList<DummyItem>(), emptyList()),
            keyOf = { it.id }
        )

        assertEquals(0, fused.size)
    }
}
