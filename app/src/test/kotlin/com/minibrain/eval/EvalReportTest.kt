package com.minibrain.eval

import com.minibrain.ai.rag.Citation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTest {

    private fun cit(path: String) = Citation(headingPath = path, snippet = "", relativePath = path)

    private val result = EvalMetrics.computeObservations(
        listOf(
            EvalObservation(EvalCase("hit", "当たる質問", listOf("a.md")), listOf(cit("a.md")), durationMs = 3_000),
            EvalObservation(
                EvalCase("miss", "外れる質問", listOf("b.md", "c.md")),
                citations = listOf(cit("x.md")),
                candidates = listOf(cit("x.md"), cit("b.md")),
                durationMs = 62_000,
            ),
            EvalObservation(EvalCase("err", "落ちる質問", listOf("d.md")), emptyList(), error = "timeout"),
        ),
        k = 10,
    )

    @Test
    fun `取りこぼしの理由とエラーが載る`() {
        val md = EvalReport.toMarkdown(result, "検索評価")

        assertTrue(md.startsWith("# 検索評価"))
        assertTrue(md.contains("- b.md: 絞り込みで落ちた"))
        assertTrue(md.contains("- c.md: 候補に無い"))
        assertTrue(md.contains("- エラー: timeout"))
        assertTrue(md.contains("- 上位: x.md"))
        // 全部当たったケースは取りこぼし欄に出さない
        assertFalse(md.contains("[hit]"))
        assertTrue(md.contains("| hit | 1.00 | 1.00 | 1.00 | 3.0 |"))
        assertTrue(md.contains("所要時間: 1分5秒"))
    }

    @Test
    fun `インデックスに無いパスがあれば警告欄を出す`() {
        assertTrue(EvalReport.toMarkdown(result, "t", listOf("typo.md")).contains("## インデックスに無い正解パス\n- typo.md"))
        assertFalse(EvalReport.toMarkdown(result, "t").contains("インデックスに無い正解パス"))
    }
}
