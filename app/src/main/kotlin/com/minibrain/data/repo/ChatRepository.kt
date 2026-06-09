package com.minibrain.data.repo

import com.minibrain.data.db.daos.ChatMessageDao
import com.minibrain.data.db.daos.ChatSessionDao
import com.minibrain.data.db.entities.ChatMessageEntity
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.db.entities.MessageRole
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
) {
    fun observeSessions(): Flow<List<ChatSessionEntity>> = sessionDao.observeAll()

    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        messageDao.observeBySession(sessionId)

    suspend fun getOrCreateSession(title: String = "新しいチャット"): Long {
        val latest = sessionDao.getLatest()
        return latest?.id ?: sessionDao.insert(ChatSessionEntity(title = title))
    }

    suspend fun createSession(title: String = "新しいチャット"): Long =
        sessionDao.insert(ChatSessionEntity(title = title))

    suspend fun addMessage(sessionId: Long, role: MessageRole, content: String, citationsJson: String = "[]"): Long =
        messageDao.insert(ChatMessageEntity(sessionId = sessionId, role = role, content = content, citationsJson = citationsJson))

    suspend fun getRecentHistory(sessionId: Long, limit: Int = 6): List<ChatMessageEntity> =
        messageDao.getRecentBySession(sessionId, limit).reversed()

    suspend fun updateSessionTitle(id: Long, title: String) = sessionDao.updateTitle(id, title)

    suspend fun deleteSession(id: Long) = sessionDao.deleteById(id)

    suspend fun clearAll() {
        sessionDao.deleteAll() // CASCADE で messages も削除される
    }
}
