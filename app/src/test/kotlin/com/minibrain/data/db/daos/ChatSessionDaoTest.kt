package com.minibrain.data.db.daos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatSessionDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.chatSessionDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndObserveAll() = runBlocking {
        val session1 = ChatSessionEntity(title = "Test 1", createdAt = 1000)
        val session2 = ChatSessionEntity(title = "Test 2", createdAt = 2000)

        dao.insert(session1)
        dao.insert(session2)

        val sessions = dao.observeAll().first()

        assertEquals(2, sessions.size)
        // Check order (DESC by createdAt)
        assertEquals("Test 2", sessions[0].title)
        assertEquals("Test 1", sessions[1].title)
    }

    @Test
    fun getLatest() = runBlocking {
        val session1 = ChatSessionEntity(title = "Test 1", createdAt = 1000)
        val session2 = ChatSessionEntity(title = "Test 2", createdAt = 2000)

        dao.insert(session1)
        dao.insert(session2)

        val latest = dao.getLatest()
        assertEquals("Test 2", latest?.title)
    }

    @Test
    fun updateTitle() = runBlocking {
        val session = ChatSessionEntity(title = "Old Title", createdAt = 1000)
        val id = dao.insert(session)

        dao.updateTitle(id, "New Title")

        val latest = dao.getLatest()
        assertEquals("New Title", latest?.title)
    }

    @Test
    fun deleteById() = runBlocking {
        val session1 = ChatSessionEntity(title = "Test 1", createdAt = 1000)
        val session2 = ChatSessionEntity(title = "Test 2", createdAt = 2000)

        val id1 = dao.insert(session1)
        val id2 = dao.insert(session2)

        dao.deleteById(id1)

        val sessions = dao.observeAll().first()
        assertEquals(1, sessions.size)
        assertEquals(id2, sessions[0].id)
    }

    @Test
    fun deleteAll() = runBlocking {
        val session1 = ChatSessionEntity(title = "Test 1", createdAt = 1000)
        val session2 = ChatSessionEntity(title = "Test 2", createdAt = 2000)

        dao.insert(session1)
        dao.insert(session2)

        dao.deleteAll()

        val sessions = dao.observeAll().first()
        assertEquals(0, sessions.size)
    }
}
