package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AnswerPromptBuilderTest {

    @Test
    fun testBuildDirectAnswerPrompt() {
        val question = "Hello world"
        val history = listOf("user" to "Hi", "assistant" to "Hello! How can I help?")
        val prompt = AnswerPromptBuilder.buildDirectAnswerPrompt(question, history)

        assertTrue(prompt.contains("ユーザー: Hi"))
        assertTrue(prompt.contains("アシスタント: Hello! How can I help?"))
        assertTrue(prompt.contains("ユーザー: Hello world"))
    }

    @Test
    fun testBuildAnswerPrompt_emptyCitations() {
        val context = AnswerContext(
            question = "Who are you?",
            citations = emptyList(),
            history = emptyList()
        )
        val prompt = AnswerPromptBuilder.buildAnswerPrompt(context)
        assertTrue(prompt.contains("知識ベースに関連する情報が見つかりませんでした"))
        assertTrue(prompt.contains("ユーザー: Who are you?"))
    }

    @Test
    fun testBuildAnswerPrompt_withCitations() {
        val citation1 = Citation(
            headingPath = "doc1.md",
            snippet = "Some content about AI.",
            source = SourceType.VECTOR
        )
        val context = AnswerContext(
            question = "Tell me about AI",
            citations = listOf(citation1),
            history = emptyList()
        )
        val prompt = AnswerPromptBuilder.buildAnswerPrompt(context)
        assertTrue(prompt.contains("あなたはユーザーのパーソナルアシスタントです。以下の「知識ベース」を参考にして質問に答えてください"))
        assertTrue(prompt.contains("### doc1.md"))
        assertTrue(prompt.contains("Some content about AI."))
        assertTrue(prompt.contains("ユーザー: Tell me about AI"))
    }

    @Test
    fun testBuildAnswerPrompt_withDateRangeAndDatedSnippets() {
        // [日付: YYYY-MM-DD] prefix exists
        val citation = Citation(
            headingPath = "diary.md",
            snippet = "[日付: 2023-01-01] Had a good day."
        )
        val dateRange = DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 1, 31))
        val context = AnswerContext(
            question = "What did I do in Jan 2023?",
            citations = listOf(citation),
            history = emptyList(),
            dateRange = dateRange
        )
        val prompt = AnswerPromptBuilder.buildAnswerPrompt(context)
        assertTrue(prompt.contains("【期間クエリの解釈】"))
        assertTrue(prompt.contains("2023-01-01 〜 2023-01-31"))
        assertTrue(prompt.contains("1. 各 snippet から日付を拾う優先順位:"))
    }

    @Test
    fun testBuildAnswerPrompt_withDateRangeNoDatedSnippets() {
        val citation = Citation(
            headingPath = "diary.md",
            snippet = "Just some content without date prefix."
        )
        val dateRange = DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 1, 31))
        val context = AnswerContext(
            question = "What did I do in Jan 2023?",
            citations = listOf(citation),
            history = emptyList(),
            dateRange = dateRange
        )
        val prompt = AnswerPromptBuilder.buildAnswerPrompt(context)
        assertTrue(prompt.contains("【期間クエリの解釈】"))
        assertTrue(prompt.contains("2023-01-01 〜 2023-01-31 の期間に解釈済みです"))
        assertTrue(prompt.contains("`[日付:]` プレフィックス付き候補は見つかりませんでした"))
    }

    @Test
    fun testBuildAnswerPrompt_isDateQuery() {
        val citation = Citation(
            headingPath = "travel.md",
            snippet = "Travel to Kyoto on 2022/05/01."
        )
        val context = AnswerContext(
            question = "いつ京都に行きましたか？", // Using date-related words to trigger DateResolver.isDateQuery
            citations = listOf(citation),
            history = emptyList()
        )
        val prompt = AnswerPromptBuilder.buildAnswerPrompt(context)
        assertTrue(prompt.contains("【日付に関する質問】"))
        assertTrue(prompt.contains("該当ファイル名がクエリに含まれている場合は、そのファイルの本文中の日付を主な根拠として「いつ」かを具体的に答えてください"))
    }
}
