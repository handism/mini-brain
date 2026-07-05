package com.minibrain.data.repo

import com.minibrain.data.db.daos.ChatMessageDao
import com.minibrain.data.db.daos.ChatSessionDao
import com.minibrain.data.db.entities.ChatMessageEntity
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.db.entities.MessageRole
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {

    @MockK
    private lateinit var sessionDao: ChatSessionDao

    @MockK
    private lateinit var messageDao: ChatMessageDao

    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository = ChatRepository(sessionDao, messageDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun observeSessions_returnsFlow() {
        val flow = flowOf(emptyList<ChatSessionEntity>())
        every { sessionDao.observeAll() } returns flow

        val result = repository.observeSessions()
        assertEquals(flow, result)
    }

    @Test
    fun observeMessages_returnsFlow() {
        val flow = flowOf(emptyList<ChatMessageEntity>())
        val sessionId = 1L
        every { messageDao.observeBySession(sessionId) } returns flow

        val result = repository.observeMessages(sessionId)
        assertEquals(flow, result)
    }

    @Test
    fun getOrCreateSession_whenLatestExists_returnsLatestId() = runTest {
        val session = ChatSessionEntity(id = 10L, title = "Existing")
        coEvery { sessionDao.getLatest() } returns session

        val id = repository.getOrCreateSession()
        assertEquals(10L, id)
        coVerify(exactly = 0) { sessionDao.insert(any()) }
    }

    @Test
    fun getOrCreateSession_whenLatestDoesNotExist_createsNewAndReturnsId() = runTest {
        coEvery { sessionDao.getLatest() } returns null
        coEvery { sessionDao.insert(any()) } returns 20L

        val id = repository.getOrCreateSession("New Title")
        assertEquals(20L, id)
        coVerify { sessionDao.insert(match { it.title == "New Title" }) }
    }

    @Test
    fun createSession_insertsAndReturnsId() = runTest {
        coEvery { sessionDao.insert(any()) } returns 30L

        val id = repository.createSession("Created")
        assertEquals(30L, id)
        coVerify { sessionDao.insert(match { it.title == "Created" }) }
    }

    @Test
    fun addMessage_insertsAndReturnsId() = runTest {
        coEvery { messageDao.insert(any()) } returns 40L

        val id = repository.addMessage(
            sessionId = 1L,
            role = MessageRole.USER,
            content = "Hello",
            citationsJson = "[]"
        )

        assertEquals(40L, id)
        coVerify {
            messageDao.insert(match {
                it.sessionId == 1L &&
                it.role == MessageRole.USER &&
                it.content == "Hello" &&
                it.citationsJson == "[]"
            })
        }
    }

    @Test
    fun getRecentHistory_returnsReversedList() = runTest {
        val sessionId = 1L
        val msg1 = ChatMessageEntity(id = 1L, sessionId = sessionId, role = MessageRole.USER, content = "Hi")
        val msg2 = ChatMessageEntity(id = 2L, sessionId = sessionId, role = MessageRole.ASSISTANT, content = "Hello")

        // DAO returns newest first (DESC)
        coEvery { messageDao.getRecentBySession(sessionId, 6) } returns listOf(msg2, msg1)

        val result = repository.getRecentHistory(sessionId, 6)

        // Repository should reverse it to chronological order
        assertEquals(listOf(msg1, msg2), result)
    }

    @Test
    fun updateSessionTitle_callsDao() = runTest {
        coEvery { sessionDao.updateTitle(any(), any()) } returns Unit

        repository.updateSessionTitle(1L, "Updated")

        coVerify { sessionDao.updateTitle(1L, "Updated") }
    }

    @Test
    fun deleteSession_callsDao() = runTest {
        coEvery { sessionDao.deleteById(any()) } returns Unit

        repository.deleteSession(1L)

        coVerify { sessionDao.deleteById(1L) }
    }

    @Test
    fun clearAll_callsDao() = runTest {
        coEvery { sessionDao.deleteAll() } returns Unit

        repository.clearAll()

        coVerify { sessionDao.deleteAll() }
    }
}
