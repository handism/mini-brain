package com.minibrain.eval

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EvalMetricsTest {

    private fun cit(path: String, headingPath: String = path) = Citation(
        headingPath = headingPath,
        snippet = "",
        relativePath = path,
        source = SourceType.UNKNOWN,
    )

    @Test
    fun `Precision Recall MRR 計算 — 全件正解`() {
        val case = EvalCase("c1", "q1", listOf("a.md", "b.md"))
        val citations = listOf(cit("a.md"), cit("b.md"), cit("c.md"))
        val result = EvalMetrics.compute(listOf(case to citations), k = 3)

        // 上位 3 件中 hit=2 → P=2/3
        assertEquals(2.0 / 3.0, result.precisionAtK, 1e-9)
        // 正解 2 件中 hit=2 → R=1.0
        assertEquals(1.0, result.recallAtK, 1e-9)
        // 最初の hit が rank 1 → MRR=1.0
        assertEquals(1.0, result.mrr, 1e-9)
    }

    @Test
    fun `正解が下位にある場合 MRR は順位の逆数`() {
        val case = EvalCase("c2", "q2", listOf("target.md"))
        val citations = listOf(cit("x.md"), cit("y.md"), cit("target.md"))
        val result = EvalMetrics.compute(listOf(case to citations), k = 5)

        assertEquals(1.0 / 3.0, result.precisionAtK, 1e-9)
        assertEquals(1.0, result.recallAtK, 1e-9)
        assertEquals(1.0 / 3.0, result.mrr, 1e-9)
    }

    @Test
    fun `正解が含まれない場合は全指標 0`() {
        val case = EvalCase("c3", "q3", listOf("target.md"))
        val citations = listOf(cit("x.md"), cit("y.md"))
        val result = EvalMetrics.compute(listOf(case to citations), k = 5)

        assertEquals(0.0, result.precisionAtK, 1e-9)
        assertEquals(0.0, result.recallAtK, 1e-9)
        assertEquals(0.0, result.mrr, 1e-9)
    }

    @Test
    fun `K で打ち切られる`() {
        val case = EvalCase("c4", "q4", listOf("a.md", "b.md"))
        val citations = listOf(cit("a.md"), cit("x.md"), cit("b.md"))
        val result = EvalMetrics.compute(listOf(case to citations), k = 2)

        // 上位 2 件: a.md, x.md → hit=1, P=0.5
        assertEquals(0.5, result.precisionAtK, 1e-9)
        // 正解 2 件中 hit=1 (b.md は K=2 から漏れる) → R=0.5
        assertEquals(0.5, result.recallAtK, 1e-9)
        assertEquals(1.0, result.mrr, 1e-9)
    }

    @Test
    fun `relativePath の大小は無視される`() {
        val case = EvalCase("c5", "q5", listOf("Folder/Note.MD"))
        val citations = listOf(cit("folder/note.md"))
        val result = EvalMetrics.compute(listOf(case to citations), k = 1)
        assertEquals(1.0, result.precisionAtK, 1e-9)
        assertEquals(1.0, result.recallAtK, 1e-9)
    }

    @Test
    fun `複数ケースはマクロ平均`() {
        val a = EvalCase("c1", "q1", listOf("x.md"))
        val b = EvalCase("c2", "q2", listOf("y.md"))
        val results = listOf(
            a to listOf(cit("x.md")),       // P=1, R=1, MRR=1
            b to listOf(cit("z.md")),       // P=0, R=0, MRR=0
        )
        val result = EvalMetrics.compute(results, k = 1)
        assertEquals(0.5, result.precisionAtK, 1e-9)
        assertEquals(0.5, result.recallAtK, 1e-9)
        assertEquals(0.5, result.mrr, 1e-9)
    }

    @Test
    fun `正解がない場合で取得結果もない場合は P=1 R=1`() {
        val case = EvalCase("c6", "q6", emptyList())
        val citations = emptyList<Citation>()
        val result = EvalMetrics.compute(listOf(case to citations), k = 3)
        assertEquals(1.0, result.precisionAtK, 1e-9)
        assertEquals(1.0, result.recallAtK, 1e-9)
    }

    @Test
    fun `正解がない場合で取得結果がある場合は P=0 R=1`() {
        val case = EvalCase("c7", "q7", emptyList())
        val citations = listOf(cit("wrong.md"))
        val result = EvalMetrics.compute(listOf(case to citations), k = 3)
        assertEquals(0.0, result.precisionAtK, 1e-9)
        assertEquals(1.0, result.recallAtK, 1e-9)
    }

    @Test
    fun `Kが0以下の場合はIllegalArgumentExceptionを投げる`() {
        val case = EvalCase("c1", "q1", listOf("a.md"))
        val citations = listOf(cit("a.md"))

        assertThrows(IllegalArgumentException::class.java) {
            EvalMetrics.compute(listOf(case to citations), k = 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            EvalMetrics.compute(listOf(case to citations), k = -1)
        }
    }
}
