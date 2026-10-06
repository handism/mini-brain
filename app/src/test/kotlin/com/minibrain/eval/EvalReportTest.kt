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
            // 正解が候補の 21 位にあり、Reranker（先頭 20 件）に渡っていない
            EvalObservation(
                EvalCase("deep", "深い質問", listOf("deep.md")),
                citations = listOf(cit("x.md")),
                candidates = (0 until 20).map { cit("n$it.md") } + cit("deep.md"),
            ),
        ),
        k = 10,
    )

    @Test
    fun `取りこぼしの理由とエラーが載る`() {
        val md = EvalReport.toMarkdown(result, "検索評価")

        assertTrue(md.startsWith("# 検索評価"))
        assertTrue(md.contains("- b.md: Reranker が落とした（候補 2 位）"))
        assertTrue(md.contains("- deep.md: Reranker に渡らず（候補 21 位）"))
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

    @Test
    fun `ベクトル類似度の欄は類似度があるときだけ出す`() {
        assertFalse(EvalReport.toMarkdown(result, "t").contains("## ベクトル類似度"))

        val withVector = EvalMetrics.computeObservations(
            listOf(
                EvalObservation(
                    EvalCase("v", "q", listOf("a.md")), listOf(cit("a.md")),
                    vectorHits = listOf(cit("a.md").copy(score = 0.82f), cit("x.md").copy(score = 0.88f)),
                ),
            ),
            k = 10,
        )
        val md = EvalReport.toMarkdown(withVector, "t")
        assertTrue(md.contains("- 正解チャンクの最高類似度: 最小 0.82 / 中央値 0.82 / 最大 0.82（1 件）"))
        assertTrue(md.contains("- 正解以外の最高類似度: 最小 0.88"))
        assertTrue(md.contains("| v | 1.00 | 1.00 | 1.00 | 0.0 | 0.82 | 0.88 |"))
    }
}

