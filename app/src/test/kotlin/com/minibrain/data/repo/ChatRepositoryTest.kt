package com.minibrain.data.repo

import io.mockk.coVerifyOrder
import com.minibrain.data.db.entities.ChatSessionSummary
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
        val flow = flowOf(emptyList<ChatSessionSummary>())
        every { sessionDao.observeSummaries() } returns flow

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
    fun removeLastExchange_deletesQuestionAndAnswer() = runTest {
        val question = ChatMessageEntity(id = 1L, sessionId = 5L, role = MessageRole.USER, content = "q")
        val answer = ChatMessageEntity(id = 2L, sessionId = 5L, role = MessageRole.ASSISTANT, content = "a")
        coEvery { messageDao.getRecentBySession(5L, 2) } returns listOf(answer, question)
        coEvery { messageDao.deleteByIds(any()) } returns Unit

        assertEquals("q", repository.removeLastExchange(5L))
        coVerify { messageDao.deleteByIds(listOf(2L, 1L)) }
    }

    @Test
    fun removeLastExchange_deletesUnansweredQuestion() = runTest {
        val answer = ChatMessageEntity(id = 1L, sessionId = 5L, role = MessageRole.ASSISTANT, content = "a")
        val question = ChatMessageEntity(id = 2L, sessionId = 5L, role = MessageRole.USER, content = "q")
        coEvery { messageDao.getRecentBySession(5L, 2) } returns listOf(question, answer)
        coEvery { messageDao.deleteByIds(any()) } returns Unit

        assertEquals("q", repository.removeLastExchange(5L))
        coVerify { messageDao.deleteByIds(listOf(2L)) }
    }

    @Test
    fun removeLastExchange_withoutQuestionDoesNothing() = runTest {
        val answer = ChatMessageEntity(id = 1L, sessionId = 5L, role = MessageRole.ASSISTANT, content = "a")
        coEvery { messageDao.getRecentBySession(5L, 2) } returns listOf(answer)

        assertEquals(null, repository.removeLastExchange(5L))
        coVerify(exactly = 0) { messageDao.deleteByIds(any()) }
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
    fun deleteSession_callsDaoAndReturnsBackup() = runTest {
        val session = ChatSessionEntity(id = 1L, title = "t", createdAt = 1000L)
        val messages = listOf(ChatMessageEntity(id = 10L, sessionId = 1L, role = MessageRole.USER, content = "q"))
        coEvery { sessionDao.getById(1L) } returns session
        coEvery { messageDao.getAllBySession(1L) } returns messages
        coEvery { sessionDao.deleteById(any()) } returns Unit

        val deleted = repository.deleteSession(1L)

        coVerify { sessionDao.deleteById(1L) }
        assertEquals(DeletedSession(session, messages), deleted)
    }

    @Test
    fun deleteSession_missingSessionReturnsNull() = runTest {
        coEvery { sessionDao.getById(1L) } returns null

        assertEquals(null, repository.deleteSession(1L))
        coVerify(exactly = 0) { sessionDao.deleteById(any()) }
    }

    @Test
    fun restoreSession_reinsertsSessionAndMessages() = runTest {
        val session = ChatSessionEntity(id = 1L, title = "t", createdAt = 1000L)
        val messages = listOf(ChatMessageEntity(id = 10L, sessionId = 1L, role = MessageRole.USER, content = "q"))
        coEvery { sessionDao.insert(session) } returns 1L
        coEvery { messageDao.insertAll(messages) } returns Unit

        repository.restoreSession(DeletedSession(session, messages))

        coVerifyOrder {
            sessionDao.insert(session)
            messageDao.insertAll(messages)
        }
    }

    @Test
    fun clearAll_callsDao() = runTest {
        coEvery { sessionDao.deleteAll() } returns Unit

        repository.clearAll()

        coVerify { sessionDao.deleteAll() }
    }
}
