package com.minibrain.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.ai.agent.QueryExpansionEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.ui.vm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], instrumentedPackages = ["androidx.loader.content"])
class MessageBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testUserMessageIsDisplayed() {
        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = "Hello from User",
            isStreaming = false
        )

        composeTestRule.setContent {
            MessageBubble(msg = userMsg, showSearchLog = false)
        }

        composeTestRule.onNodeWithText("Hello from User").assertIsDisplayed()
        // 自分の発言は長押しで選択できるので、コピーボタンは出さない
        composeTestRule.onNodeWithContentDescription("コピー").assertDoesNotExist()
    }

    @Test
    fun testAssistantMessageIsDisplayed() {
        val assistMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Hello from Assistant",
            isStreaming = false
        )

        composeTestRule.setContent {
            MessageBubble(msg = assistMsg, showSearchLog = false)
        }

        composeTestRule.onNodeWithText("Hello from Assistant").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("コピー").assertIsDisplayed()
    }

    @Test
    fun testStreamingMessageDoesNotShowCopyButton() {
        val assistMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Streaming...",
            isStreaming = true
        )

        composeTestRule.setContent {
            MessageBubble(msg = assistMsg, showSearchLog = false)
        }

        composeTestRule.onNodeWithText("Streaming...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("コピー").assertDoesNotExist()
    }

    @Test
    fun testCitationsAreDisplayed() {
        val citation = Citation(
            headingPath = "Test Document",
            snippet = "This is a test snippet."
        )
        val assistMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Here is the answer.",
            citations = listOf(citation),
            isStreaming = false
        )

        composeTestRule.setContent {
            MessageBubble(msg = assistMsg, showSearchLog = false)
        }

        // 引用元はチップとして常に見える
        composeTestRule.onNodeWithText("Test Document").assertIsDisplayed()
        composeTestRule.onNodeWithText("引用元 (1)").assertIsDisplayed()

        // 引用箇所（スニペット）は展開するまで出ない
        composeTestRule.onNodeWithText("This is a test snippet.").assertDoesNotExist()

        composeTestRule.onNodeWithText("引用元 (1)").performClick()

        composeTestRule.onNodeWithText("This is a test snippet.").assertIsDisplayed()
    }

    @Test
    fun testSearchLogIsDisplayedWhenEnabled() {
        val traceEvent = QueryExpansionEvent(listOf("test query"))
        val assistMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Found results.",
            traceEvents = listOf(traceEvent),
            isStreaming = false
        )

        composeTestRule.setContent {
            MessageBubble(msg = assistMsg, showSearchLog = true)
        }

        composeTestRule.onNodeWithText("検索ログ").assertIsDisplayed()
    }

    @Test
    fun testSearchLogIsHiddenWhenDisabled() {
        val traceEvent = QueryExpansionEvent(listOf("test query"))
        val assistMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Found results.",
            traceEvents = listOf(traceEvent),
            isStreaming = false
        )

        composeTestRule.setContent {
            MessageBubble(msg = assistMsg, showSearchLog = false)
        }

        composeTestRule.onNodeWithText("検索ログ").assertDoesNotExist()
    }

    @Test
    fun testCitationChipOpensFile() {
        val citation = Citation(headingPath = "見出し", snippet = "s", docId = 1L, relativePath = "diary/2026-10-01.md")
        val assistMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "answer", citations = listOf(citation))
        var opened: Citation? = null

        composeTestRule.setContent {
            MessageBubble(msg = assistMsg, showSearchLog = false, onOpenCitation = { opened = it })
        }

        composeTestRule.onNodeWithText("2026-10-01.md").performClick()
        assertEquals(citation, opened)
    }

    @Test
    fun distinctCitationSourcesMergesChunksOfSameFile() {
        val a1 = Citation(headingPath = "A > 1", snippet = "", docId = 1L, relativePath = "notes/a.md")
        val a2 = Citation(headingPath = "A > 2", snippet = "", docId = 1L, relativePath = "notes/a.md")
        val b = Citation(headingPath = "B", snippet = "", docId = 2L, relativePath = "b.md")

        assertEquals(listOf(a1, b), distinctCitationSources(listOf(a1, a2, b)))
        assertEquals("a.md", citationChipLabel(a1))
        assertEquals("フォルダ要約", citationChipLabel(Citation(headingPath = "フォルダ要約", snippet = "")))
    }
}
