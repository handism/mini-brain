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
import com.minibrain.data.repo.RemovedExchange
import com.minibrain.dataStore
import com.minibrain.di.AppContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { container.chatRepository } returns chatRepo

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
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { container.chatRepository } returns chatRepo

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
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val agentPipeline = mockk<AgentPipeline>(relaxed = true)
        every { container.chatRepository } returns chatRepo
        every { container.agentPipeline } returns agentPipeline

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
        assertNull(viewModel.error.value)

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
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { container.chatRepository } returns chatRepo

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
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val agentPipeline = mockk<AgentPipeline>(relaxed = true)
        every { container.chatRepository } returns chatRepo
        every { container.agentPipeline } returns agentPipeline

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
        // キャンセルはエラーではないので、エラーメッセージは出さない
        assertNull(viewModel.error.value)
        coVerify(exactly = 1) { chatRepo.addMessage(100L, MessageRole.ASSISTANT, "Start...", any()) }
    }

    private fun raceFixture(): Triple<ChatViewModel, ChatRepository, AgentPipeline> {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        val repository = mockk<ChatRepository>(relaxed = true)
        val pipeline = mockk<AgentPipeline>(relaxed = true)
        every { app.container } returns container
        every { container.chatRepository } returns repository
        every { container.agentPipeline } returns pipeline
        val store = mockk<DataStore<Preferences>>()
        val prefs = mockk<Preferences>(relaxed = true)
        every { prefs[stringPreferencesKey("tree_uri")] } returns ""
        every { prefs[booleanPreferencesKey("show_search_log")] } returns false
        every { store.data } returns flowOf(prefs)
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns store
        every { repository.observeMessages(any()) } returns flowOf(emptyList())
        coEvery { repository.getRecentHistory(any()) } returns emptyList()
        return Triple(createViewModel(app, 100L), repository, pipeline)
    }

    @Test
    fun `new session is ignored during answer generation`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        val finish = CompletableDeferred<Unit>()
        coEvery { pipeline.run(any(), any(), any(), any()) } returns AgentResult(
            emptyList(), flow { finish.await(); emit("answer") }, emptyList(),
        )
        runCurrent()
        vm.sendMessage("question")
        runCurrent()
        vm.newSession()
        runCurrent()
        coVerify(exactly = 0) { repository.createSession(any()) }
        finish.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.ASSISTANT, "answer", any()) }
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun `regeneration locks before deletion and rejects competing operations`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        val deletion = CompletableDeferred<Unit>()
        coEvery { repository.removeLastExchange(100L) } coAnswers {
            deletion.await()
            RemovedExchange("original question", emptyList())
        }
        coEvery { pipeline.run(any(), any(), any(), any()) } returns AgentResult(
            emptyList(), flowOf("replacement"), emptyList(),
        )
        runCurrent()
        vm.regenerate()
        assertTrue(vm.isGenerating.value)
        runCurrent()
        vm.regenerate()
        vm.sendMessage("competing question")
        vm.newSession()
        runCurrent()
        coVerify(exactly = 1) { repository.removeLastExchange(100L) }
        coVerify(exactly = 0) { repository.createSession(any()) }
        coVerify(exactly = 0) { repository.addMessage(any(), MessageRole.USER, any(), any()) }
        deletion.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.USER, "original question", any()) }
        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.ASSISTANT, "replacement", any()) }
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun `regeneration without an exchange releases the lock`() = runTest {
        val (vm, repository, _) = raceFixture()
        coEvery { repository.removeLastExchange(100L) } returns null
        runCurrent()
        vm.regenerate()
        advanceUntilIdle()
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun `session creation rejects sends and repeated creation while pending`() = runTest {
        val (vm, repository, _) = raceFixture()
        val creation = CompletableDeferred<Long>()
        coEvery { repository.createSession(any()) } coAnswers { creation.await() }
        runCurrent()
        vm.newSession()
        runCurrent()
        vm.newSession()
        vm.sendMessage("competing question")
        vm.regenerate()
        runCurrent()
        coVerify(exactly = 1) { repository.createSession(any()) }
        coVerify(exactly = 0) { repository.addMessage(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.removeLastExchange(any()) }
        creation.complete(200L)
        advanceUntilIdle()
        assertTrue(vm.messages.value.isEmpty())
    }


    @Test
    fun `stopping an answer locks operations until partial answer is saved`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        val save = CompletableDeferred<Unit>()
        coEvery { repository.addMessage(100L, MessageRole.ASSISTANT, "partial", any()) } coAnswers {
            save.await()
            42L
        }
        coEvery { pipeline.run(any(), any(), any(), any()) } returns AgentResult(
            emptyList(), flow { emit("partial"); CompletableDeferred<Unit>().await() }, emptyList(),
        )
        runCurrent()
        vm.sendMessage("question")
        runCurrent()
        vm.cancelGeneration()
        runCurrent()
        assertTrue(vm.isGenerating.value)
        vm.sendMessage("competing")
        vm.regenerate()
        vm.newSession()
        vm.cancelGeneration()
        runCurrent()
        coVerify(exactly = 0) { repository.createSession(any()) }
        coVerify(exactly = 0) { repository.removeLastExchange(any()) }
        coVerify(exactly = 0) { repository.addMessage(any(), MessageRole.USER, "competing", any()) }
        save.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.isGenerating.value)
        assertEquals("partial", vm.messages.value.last().content)
        assertEquals(42L, vm.messages.value.last().id)
        assertFalse(vm.messages.value.last().isStreaming)
        assertNull(vm.error.value)
        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.ASSISTANT, "partial", any()) }
    }

    @Test
    fun `stopping during search does not save an empty answer`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        coEvery { pipeline.run(any(), any(), any(), any()) } coAnswers { CompletableDeferred<AgentResult>().await() }
        runCurrent()
        vm.sendMessage("question")
        runCurrent()
        vm.cancelGeneration()
        advanceUntilIdle()
        assertFalse(vm.isGenerating.value)
        assertEquals(listOf(MessageRole.USER), vm.messages.value.map { it.role })
        coVerify(exactly = 0) { repository.addMessage(any(), MessageRole.ASSISTANT, any(), any()) }
    }

    @Test
    fun `stopping before generation starts releases operation lock`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        coEvery { pipeline.run(any(), any(), any(), any()) } returns AgentResult(
            emptyList(), flowOf("answer"), emptyList(),
        )
        runCurrent()
        vm.sendMessage("cancelled before dispatch")
        vm.cancelGeneration()
        advanceUntilIdle()
        assertFalse(vm.isGenerating.value)
        vm.sendMessage("next question")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.ASSISTANT, "answer", any()) }
    }

    @Test
    fun `stopping during completed answer save does not insert twice`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        val save = CompletableDeferred<Unit>()
        coEvery { repository.addMessage(100L, MessageRole.ASSISTANT, "answer", any()) } coAnswers {
            save.await()
            42L
        }
        coEvery { pipeline.run(any(), any(), any(), any()) } returns AgentResult(
            emptyList(), flowOf("answer"), emptyList(),
        )
        runCurrent()
        vm.sendMessage("question")
        runCurrent()
        vm.cancelGeneration()
        runCurrent()
        assertTrue(vm.isGenerating.value)
        save.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.isGenerating.value)
        assertEquals(42L, vm.messages.value.last().id)
        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.ASSISTANT, "answer", any()) }
    }

    @Test
    fun `isNegativeResponse only checks the opening of the answer`() {
        assertTrue(isNegativeResponse("申し訳ありませんが、資料からは分かりませんでした。"))
        val longAnswer = "スパイス堂には 2024/11/03 に初めて訪れています。" + "あ".repeat(NEGATIVE_CHECK_CHARS) +
            "なお再訪の日付は分かりません。"
        assertFalse(isNegativeResponse(longAnswer))
    }

    @Test
    fun `回答の生成が最初のトークンの前に失敗したら空の回答を保存しない`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val agentPipeline = mockk<AgentPipeline>(relaxed = true)
        every { container.chatRepository } returns chatRepo
        every { container.agentPipeline } returns agentPipeline

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val sessionId = 100L
        coEvery { chatRepo.getOrCreateSession() } returns sessionId
        every { chatRepo.observeMessages(sessionId) } returns flowOf(emptyList())
        coEvery { chatRepo.getRecentHistory(sessionId) } returns emptyList()
        coEvery { agentPipeline.run(any(), any(), any(), any()) } returns AgentResult(
            citations = emptyList(),
            answerFlow = flow<String> { throw IllegalStateException("LlmService not initialized") },
            traceEvents = emptyList(),
        )

        val viewModel = createViewModel(app)
        advanceUntilIdle()
        viewModel.sendMessage("質問")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.addMessage(any(), MessageRole.ASSISTANT, any(), any()) }
        assertEquals(ChatErrorKind.GENERATION, viewModel.error.value?.kind)
        assertEquals(listOf(MessageRole.USER), viewModel.messages.value.map { it.role })
        assertFalse(viewModel.isGenerating.value)
    }

    @Test
    fun `回答が空で終わったときも保存せずに失敗として出す`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val agentPipeline = mockk<AgentPipeline>(relaxed = true)
        every { container.chatRepository } returns chatRepo
        every { container.agentPipeline } returns agentPipeline

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[booleanPreferencesKey("show_search_log")] } returns true
        every { testPrefs[stringPreferencesKey("tree_uri")] } returns ""
        every { testDataStore.data } returns flowOf(testPrefs)
        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val sessionId = 100L
        coEvery { chatRepo.getOrCreateSession() } returns sessionId
        every { chatRepo.observeMessages(sessionId) } returns flowOf(emptyList())
        coEvery { chatRepo.getRecentHistory(sessionId) } returns emptyList()
        coEvery { agentPipeline.run(any(), any(), any(), any()) } returns AgentResult(
            citations = emptyList(),
            answerFlow = flowOf(" ", "\n"),
            traceEvents = emptyList(),
        )

        val viewModel = createViewModel(app)
        advanceUntilIdle()
        viewModel.sendMessage("質問")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.addMessage(any(), MessageRole.ASSISTANT, any(), any()) }
        assertEquals(ChatError(ChatErrorKind.GENERATION, null), viewModel.error.value)
        assertFalse(viewModel.isGenerating.value)
    }

    private val originalQuestion = ChatMessageEntity(id = 10, sessionId = 100L, role = MessageRole.USER, content = "q", createdAt = 0L, citationsJson = "[]")
    private val originalAnswer = ChatMessageEntity(id = 11, sessionId = 100L, role = MessageRole.ASSISTANT, content = "old answer", createdAt = 0L, citationsJson = "[]")

    @Test
    fun `再生成を出力前に止めたら元のやり取りに戻す`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        coEvery { repository.removeLastExchange(100L) } returns RemovedExchange("q", listOf(originalQuestion, originalAnswer))
        coEvery { repository.addMessage(100L, MessageRole.USER, "q", any()) } returns 12L
        coEvery { pipeline.run(any(), any(), any(), any()) } coAnswers { CompletableDeferred<AgentResult>().await() }
        runCurrent()

        vm.regenerate()
        runCurrent()
        vm.cancelGeneration()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.restoreExchange(RemovedExchange("q", listOf(originalQuestion, originalAnswer)), 12L) }
        coVerify(exactly = 0) { repository.addMessage(any(), MessageRole.ASSISTANT, any(), any()) }
        assertEquals(listOf("q", "old answer"), vm.messages.value.map { it.content })
        assertEquals(listOf(10L, 11L), vm.messages.value.map { it.id })
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun `再生成の検索に失敗したら元のやり取りに戻す`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        coEvery { repository.removeLastExchange(100L) } returns RemovedExchange("q", listOf(originalQuestion, originalAnswer))
        coEvery { repository.addMessage(100L, MessageRole.USER, "q", any()) } returns 12L
        coEvery { pipeline.run(any(), any(), any(), any()) } throws IllegalStateException("db")
        runCurrent()

        vm.regenerate()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.restoreExchange(any(), 12L) }
        assertEquals(listOf("q", "old answer"), vm.messages.value.map { it.content })
        assertEquals(ChatErrorKind.SEARCH, vm.error.value?.kind)
    }

    @Test
    fun `再生成で途中まで出てから止めたら、途中の回答を保存して元には戻さない`() = runTest {
        val (vm, repository, pipeline) = raceFixture()
        coEvery { repository.removeLastExchange(100L) } returns RemovedExchange("q", listOf(originalQuestion, originalAnswer))
        coEvery { pipeline.run(any(), any(), any(), any()) } returns AgentResult(
            emptyList(), flow { emit("partial"); CompletableDeferred<Unit>().await() }, emptyList(),
        )
        runCurrent()

        vm.regenerate()
        runCurrent()
        vm.cancelGeneration()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.addMessage(100L, MessageRole.ASSISTANT, "partial", any()) }
        coVerify(exactly = 0) { repository.restoreExchange(any(), any()) }
    }
}
