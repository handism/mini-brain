package com.minibrain.data.db.daos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.entities.ChatMessageEntity
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.db.entities.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: ChatSessionDao
    private lateinit var messageDao: ChatMessageDao
    private var sessionId: Long = 0

    @Before
    fun createDb() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        sessionDao = db.chatSessionDao()
        messageDao = db.chatMessageDao()

        sessionId = sessionDao.insert(ChatSessionEntity(title = "Test Session", createdAt = 1000))
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndObserveBySession() = runBlocking {
        val msg1 = ChatMessageEntity(sessionId = sessionId, role = MessageRole.USER, content = "Hello", createdAt = 1000)
        val msg2 = ChatMessageEntity(sessionId = sessionId, role = MessageRole.ASSISTANT, content = "Hi there", createdAt = 2000)

        messageDao.insert(msg1)
        messageDao.insert(msg2)

        val messages = messageDao.observeBySession(sessionId).first()

        assertEquals(2, messages.size)
        // Check ASC order by createdAt
        assertEquals("Hello", messages[0].content)
        assertEquals("Hi there", messages[1].content)
    }

    @Test
    fun getRecentBySession() = runBlocking {
        val msg1 = ChatMessageEntity(sessionId = sessionId, role = MessageRole.USER, content = "First", createdAt = 1000)
        val msg2 = ChatMessageEntity(sessionId = sessionId, role = MessageRole.ASSISTANT, content = "Second", createdAt = 2000)
        val msg3 = ChatMessageEntity(sessionId = sessionId, role = MessageRole.USER, content = "Third", createdAt = 3000)

        messageDao.insert(msg1)
        messageDao.insert(msg2)
        messageDao.insert(msg3)

        val recent = messageDao.getRecentBySession(sessionId, 2)

        assertEquals(2, recent.size)
        // Check DESC order by createdAt for recent
        assertEquals("Third", recent[0].content)
        assertEquals("Second", recent[1].content)
    }

    @Test
    fun deleteBySession() = runBlocking {
        val msg1 = ChatMessageEntity(sessionId = sessionId, role = MessageRole.USER, content = "Hello", createdAt = 1000)
        messageDao.insert(msg1)

        messageDao.deleteBySession(sessionId)

        val messages = messageDao.observeBySession(sessionId).first()
        assertEquals(0, messages.size)
    }
}
