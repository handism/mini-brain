package com.minibrain.ui.vm

import com.minibrain.MiniBrainApp
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.repo.ChatRepository
import com.minibrain.di.AppContainer
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryViewModelTest {

    @MockK
    private lateinit var app: MiniBrainApp

    @MockK
    private lateinit var container: AppContainer

    @MockK
    private lateinit var chatRepository: ChatRepository

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.container } returns container
        every { container.chatRepository } returns chatRepository
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.minibrain.MiniBrainAppKt")
    }

    @Test
    fun `sessions stateflow emits sessions from repository`() = runTest(testDispatcher) {
        val sampleSessions = listOf(
            ChatSessionEntity(id = 1L, title = "Session 1", createdAt = 1000L)
        )
        every { chatRepository.observeSessions() } returns flowOf(sampleSessions)

        val viewModel = ChatHistoryViewModel(app)
        val job = backgroundScope.launch { viewModel.sessions.collect {} }
        advanceUntilIdle()

        assertEquals(sampleSessions, viewModel.sessions.value)
        job.cancel()
    }

    @Test
    fun `deleteSession calls repository deleteSession`() = runTest(testDispatcher) {
        every { chatRepository.observeSessions() } returns flowOf(emptyList())
        coEvery { chatRepository.deleteSession(1L) } returns Unit

        val viewModel = ChatHistoryViewModel(app)
        viewModel.deleteSession(1L)
        advanceUntilIdle()

        coVerify { chatRepository.deleteSession(1L) }
    }
}
