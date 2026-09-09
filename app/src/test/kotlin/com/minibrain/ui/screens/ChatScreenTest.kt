package com.minibrain.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.entities.MessageRole
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
        val errorMessageFlow = MutableStateFlow<String?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.errorMessage } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("質問を入力してください\nmdファイルの内容をもとに回答します").assertIsDisplayed()
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
        val errorMessageFlow = MutableStateFlow<String?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.errorMessage } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("Hello!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hi there!").assertIsDisplayed()
    }

    @Test
    fun testGeneratingStateDisplaysStatusAndStopButton() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        val isGeneratingFlow = MutableStateFlow(true)
        val errorMessageFlow = MutableStateFlow<String?>(null)
        val statusTextFlow = MutableStateFlow("Searching...")
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.errorMessage } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow

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
        val errorMessageFlow = MutableStateFlow("An error occurred")
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.errorMessage } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow

        composeTestRule.setContent {
            ChatScreen(onBack = {}, onOpenHistory = {}, vm = vm)
        }

        composeTestRule.onNodeWithText("An error occurred").assertIsDisplayed()
    }

    @Test
    fun testInputAndSendInteraction() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        val isGeneratingFlow = MutableStateFlow(false)
        val errorMessageFlow = MutableStateFlow<String?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.errorMessage } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow

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
        val errorMessageFlow = MutableStateFlow<String?>(null)
        val statusTextFlow = MutableStateFlow<String?>(null)
        val showSearchLogFlow = MutableStateFlow(true)

        every { vm.messages } returns messagesFlow
        every { vm.isGenerating } returns isGeneratingFlow
        every { vm.errorMessage } returns errorMessageFlow
        every { vm.statusText } returns statusTextFlow
        every { vm.showSearchLog } returns showSearchLogFlow

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
}
