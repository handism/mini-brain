package com.minibrain.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.data.repo.IndexingState
import com.minibrain.ui.vm.ChatError
import com.minibrain.ui.vm.ChatErrorKind
import com.minibrain.ui.vm.ChatMessage
import com.minibrain.ui.vm.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(instrumentedPackages = ["androidx.loader.content"])
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testEmptyStateDisplaysPlaceholder() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        val isGeneratingFlow = MutableStateFlow(false)
        val errorMessageFlow = MutableStateFlow<ChatError?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.error } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("ノートについて聞いてみましょう").assertIsDisplayed()
        composeTestRule.onNodeWithText("フォルダ内のノートをもとに回答します。内容は端末の外に送られません。").assertIsDisplayed()
    }

    @Test
    fun testReferenceFolderIsVisible() {
        val vm = relaxedVm()
        every { vm.savedTreeUri } returns MutableStateFlow<String?>("content://com.android.externalstorage.documents/tree/primary%3ANotes")
        composeTestRule.setContent { ChatScreen(onBack = {}, vm = vm) }
        composeTestRule.onNodeWithText("参照中：Notes").assertIsDisplayed()
    }

    @Test
    fun testSuggestionChipFillsInput() {
        val vm = relaxedVm()

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("最近書いたメモは？").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("送信").performClick()

        verify { vm.sendMessage("最近書いたメモは？") }
    }

    @Test
    fun testImeSendActionSendsMessage() {
        val vm = relaxedVm()

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("質問を入力...").performTextInput("IME send")
        composeTestRule.onNodeWithText("IME send").performImeAction()

        verify { vm.sendMessage("IME send") }
    }

    @Test
    fun testStatusIsShownInsideStreamingBubble() {
        val vm = relaxedVm(
            messages = listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "", isStreaming = true)),
            isGenerating = true,
            statusText = "再ランク中...",
        )

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        // 吹き出しの中に 1 回だけ出る（下部のステータス行と二重にならない）
        composeTestRule.onAllNodesWithText("再ランク中...").assertCountEquals(1)
    }

    @Test
    fun testElapsedSecondsAppearWhileWaiting() {
        val vm = relaxedVm(
            messages = listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "", isStreaming = true)),
            isGenerating = true,
            statusText = "検索中...",
        )
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("3 秒").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(3_100)
        composeTestRule.onNodeWithText("3 秒").assertIsDisplayed()
    }

    @Test
    fun testRegenerateIsShownOnlyForLastAnswer() {
        val vm = relaxedVm(
            messages = listOf(
                ChatMessage(id = 1, role = MessageRole.USER, content = "q1"),
                ChatMessage(id = 2, role = MessageRole.ASSISTANT, content = "a1"),
                ChatMessage(id = 3, role = MessageRole.USER, content = "q2"),
                ChatMessage(id = 4, role = MessageRole.ASSISTANT, content = "a2"),
            ),
        )

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onAllNodesWithContentDescription("回答を再生成").assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("回答を再生成").performClick()
        verify { vm.regenerate() }
    }

    @Test
    fun testRegenerateIsHiddenWhileGenerating() {
        val vm = relaxedVm(
            messages = listOf(
                ChatMessage(id = 1, role = MessageRole.USER, content = "q1"),
                ChatMessage(id = 2, role = MessageRole.ASSISTANT, content = "a1"),
            ),
            isGenerating = true,
        )

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithContentDescription("回答を再生成").assertDoesNotExist()
    }

    private fun relaxedVm(
        messages: List<ChatMessage> = emptyList(),
        isGenerating: Boolean = false,
        statusText: String? = null,
    ): ChatViewModel {
        val vm = mockk<ChatViewModel>(relaxed = true)
        every { vm.messages } returns MutableStateFlow(messages)
        every { vm.isGenerating } returns MutableStateFlow(isGenerating)
        every { vm.error } returns MutableStateFlow<ChatError?>(null)
        every { vm.statusText } returns MutableStateFlow(statusText)
        every { vm.showSearchLog } returns MutableStateFlow(true)
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))
        return vm
    }

    @Test
    fun testMessagesAreDisplayed() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        val messages = listOf(
            ChatMessage(id = 1, role = MessageRole.USER, content = "Hello!"),
            ChatMessage(id = 2, role = MessageRole.ASSISTANT, content = "Hi there!")
        )
        val messagesFlow = MutableStateFlow(messages)
        val isGeneratingFlow = MutableStateFlow(false)
        val errorMessageFlow = MutableStateFlow<ChatError?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.error } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("Hello!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hi there!").assertIsDisplayed()
    }

    @Test
    fun testGeneratingStateDisplaysStatusAndStopButton() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        // 送信すると質問と空の回答欄（ストリーミング中）がすぐ並び、段階の文言は回答欄に出る
        val messagesFlow = MutableStateFlow(
            listOf(
                ChatMessage(role = MessageRole.USER, content = "質問"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "", isStreaming = true),
            )
        )
        val isGeneratingFlow = MutableStateFlow(true)
        val errorMessageFlow = MutableStateFlow<ChatError?>(null)
        val statusTextFlow = MutableStateFlow("Searching...")
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.error } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("Searching...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("停止").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("停止").performClick()
        verify { vm.cancelGeneration() }
    }

    @Test
    fun testErrorStateDisplaysErrorMessage() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        val isGeneratingFlow = MutableStateFlow(false)
        val errorMessageFlow = MutableStateFlow<ChatError?>(ChatError(ChatErrorKind.GENERATION, "An error occurred"))
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.error } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("回答の生成に失敗しました").assertIsDisplayed()
        // 例外の中身は「詳細を表示」で開いたときだけ出す
        composeTestRule.onNodeWithText("An error occurred").assertDoesNotExist()
        composeTestRule.onNodeWithText("詳細を表示").performClick()
        composeTestRule.onNodeWithText("An error occurred").assertIsDisplayed()

        composeTestRule.onNodeWithText("再試行").performClick()
        verify { vm.regenerate() }
        composeTestRule.onNodeWithContentDescription("閉じる").performClick()
        verify { vm.dismissError() }
    }

    @Test
    fun testIndexingBannerIsShownWhileIndexing() {
        val vm = relaxedVm()
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Progress(3, 10, "a.md"))

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("インデックス作成中（3/10）。完了までは回答に使われないファイルがあります").assertIsDisplayed()
    }

    @Test
    fun testInputAndSendInteraction() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        val isGeneratingFlow = MutableStateFlow(false)
        val errorMessageFlow = MutableStateFlow<ChatError?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.error } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("質問を入力...").performTextInput("How does it work?")
        composeTestRule.onNodeWithContentDescription("送信").performClick()

        verify { vm.sendMessage("How does it work?") }
    }

    @Test
    fun testNavigationCallbacks() {
        var backCalled = false
        var historyCalled = false
        val vm = mockk<ChatViewModel>(relaxed = true)

        val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        val isGeneratingFlow = MutableStateFlow(false)
        val errorMessageFlow = MutableStateFlow<ChatError?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.error } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow
        every { vm.sessionTitle } returns MutableStateFlow<String?>(null)
        every { vm.indexingState } returns MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { vm.savedTreeUri } returns MutableStateFlow<String?>(null)
        every { vm.suggestions } returns MutableStateFlow(listOf("「カレー」について教えて", "最近書いたメモは？"))

        composeTestRule.setContent {
            ChatScreen(
                onBack = { backCalled = true },
                onOpenHistory = { historyCalled = true },
                vm = vm
            )
        }

        composeTestRule.onNodeWithContentDescription("戻る").performClick()
        assert(backCalled)

        composeTestRule.onNodeWithContentDescription("履歴").performClick()
        assert(historyCalled)

        composeTestRule.onNodeWithContentDescription("新しいチャット").performClick()
        verify { vm.newSession() }
    }

    @Test
    fun testTopBarShowsSessionTitle() {
        val vm = relaxedVm()
        every { vm.sessionTitle } returns MutableStateFlow<String?>("先月の日記")

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("先月の日記").assertIsDisplayed()
    }
}
