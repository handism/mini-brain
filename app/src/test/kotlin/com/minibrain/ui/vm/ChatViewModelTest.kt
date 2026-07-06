package com.minibrain.ui.vm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import com.minibrain.MiniBrainApp
import com.minibrain.ai.agent.AgentPipeline
import com.minibrain.ai.agent.AgentResult
import com.minibrain.data.db.entities.ChatMessageEntity
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.data.repo.ChatRepository
import com.minibrain.dataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.isLoggable(any(), any()) } returns true
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // do nothing
            }
        })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Timber.uprootAll()
        unmockkAll()
    }

    private fun createViewModel(
        app: MiniBrainApp,
        navSessionId: Long = -1L
    ): ChatViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to navSessionId))
        return ChatViewModel(app, savedStateHandle)
    }

    @Test
    fun `init loads existing session and messages`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { app.chatRepository } returns chatRepo

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val sessionId = 123L
        val messagesFlow = flowOf(
            listOf(
                ChatMessageEntity(id = 1, sessionId = sessionId, role = MessageRole.USER, content = "Hello", createdAt = 0L, citationsJson = "[]")
            )
        )
        every { chatRepo.observeMessages(sessionId) } returns messagesFlow

        val viewModel = createViewModel(app, sessionId)
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].content)
        assertEquals(MessageRole.USER, messages[0].role)
    }

    @Test
    fun `init creates new session if navSessionId is absent`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { app.chatRepository } returns chatRepo

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val newSessionId = 456L
        coEvery { chatRepo.getOrCreateSession() } returns newSessionId
        every { chatRepo.observeMessages(newSessionId) } returns flowOf(emptyList())

        val viewModel = createViewModel(app)
        advanceUntilIdle()

        coVerify { chatRepo.getOrCreateSession() }
        val messages = viewModel.messages.value
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `sendMessage success path`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val agentPipeline = mockk<AgentPipeline>(relaxed = true)
        every { app.chatRepository } returns chatRepo
        every { app.agentPipeline } returns agentPipeline

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val sessionId = 100L
        coEvery { chatRepo.getOrCreateSession() } returns sessionId
        every { chatRepo.observeMessages(sessionId) } returns flowOf(emptyList())
        coEvery { chatRepo.getRecentHistory(sessionId) } returns emptyList()

        val question = "What is Kotlin?"

        // Setup agent pipeline mock
        val answerFlow = flow {
            emit("It ")
            emit("is ")
            emit("a language.")
        }
        val agentResult = AgentResult(
            citations = emptyList(),
            answerFlow = answerFlow,
            traceEvents = emptyList()
        )
        coEvery {
            agentPipeline.run(any(), any(), any(), any())
        } returns agentResult

        val viewModel = createViewModel(app)
        advanceUntilIdle() // let init complete

        // Verify isGenerating is false before sending message
        assertFalse(viewModel.isGenerating.value)

        viewModel.sendMessage(question)

        // Run coroutines immediately
        testDispatcher.scheduler.advanceUntilIdle()

        // We can just check the final state because advanceUntilIdle finishes it all
        assertFalse(viewModel.isGenerating.value)
        assertNull(viewModel.errorMessage.value)

        val msgs = viewModel.messages.value
        assertEquals(2, msgs.size)
        assertEquals(MessageRole.USER, msgs[0].role)
        assertEquals("What is Kotlin?", msgs[0].content)

        assertEquals(MessageRole.ASSISTANT, msgs[1].role)
        assertEquals("It is a language.", msgs[1].content)
        assertFalse(msgs[1].isStreaming)

        coVerify { chatRepo.addMessage(sessionId, MessageRole.USER, question) }
        coVerify { chatRepo.addMessage(sessionId, MessageRole.ASSISTANT, "It is a language.", any()) }
    }

    @Test
    fun `newSession resets messages and gets new session id`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { app.chatRepository } returns chatRepo

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        coEvery { chatRepo.getOrCreateSession() } returns 100L
        every { chatRepo.observeMessages(any()) } returns flowOf(emptyList())
        coEvery { chatRepo.createSession() } returns 200L

        val viewModel = createViewModel(app)
        advanceUntilIdle()

        viewModel.newSession()
        advanceUntilIdle()

        coVerify { chatRepo.createSession() }
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun `cancelGeneration sets isGenerating to false and marks streaming message non-streaming`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val agentPipeline = mockk<AgentPipeline>(relaxed = true)
        every { app.chatRepository } returns chatRepo
        every { app.agentPipeline } returns agentPipeline

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        coEvery { chatRepo.getOrCreateSession() } returns 100L
        every { chatRepo.observeMessages(any()) } returns flowOf(emptyList())
        coEvery { chatRepo.getRecentHistory(any()) } returns emptyList()

        // Make the agent pipeline block or be slow so we can cancel it
        val answerFlow = flow {
            emit("Start...")
            kotlinx.coroutines.delay(5000)
            emit("End")
        }
        val agentResult = AgentResult(emptyList(), answerFlow, emptyList())
        coEvery { agentPipeline.run(any(), any(), any(), any()) } returns agentResult

        val viewModel = createViewModel(app)
        advanceUntilIdle()

        viewModel.sendMessage("Test")
        // Run partially
        testDispatcher.scheduler.advanceTimeBy(100)

        assertTrue(viewModel.isGenerating.value)
        val msgs = viewModel.messages.value
        val streamingMsg = msgs.last()
        assertTrue(streamingMsg.isStreaming)

        // Cancel
        viewModel.cancelGeneration()
        advanceUntilIdle()

        assertFalse(viewModel.isGenerating.value)
        val finalMsgs = viewModel.messages.value
        assertFalse(finalMsgs.last().isStreaming)
    }
}
