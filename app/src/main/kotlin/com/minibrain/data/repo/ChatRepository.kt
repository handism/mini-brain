package com.minibrain.data.repo

import com.minibrain.data.db.daos.ChatMessageDao
import com.minibrain.data.db.daos.ChatSessionDao
import com.minibrain.data.db.entities.ChatMessageEntity
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.db.entities.ChatSessionSummary
import com.minibrain.data.db.entities.MessageRole
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
) {
    fun observeSessions(): Flow<List<ChatSessionSummary>> = sessionDao.observeSummaries()

    fun observeSessionTitle(sessionId: Long): Flow<String?> = sessionDao.observeTitle(sessionId)

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

    /**
     * 履歴画面や設定の全削除で消されていないか。消えたセッションは作り直さない
     * （同じ id で作ると「元に戻す」が主キーで失敗し、消したチャットが回答だけで戻るため、ADR-080）。
     */
    suspend fun sessionExists(sessionId: Long): Boolean = sessionDao.getById(sessionId) != null

    suspend fun getRecentHistory(sessionId: Long, limit: Int = 6): List<ChatMessageEntity> =
        messageDao.getRecentBySession(sessionId, limit).reversed()

    /**
     * 最後の質問と、それへの回答（あれば）を消して返す。再生成で同じ質問を送り直すため。
     * 最後のやり取りが質問で終わっていなければ（回答だけが続くなど）何もせず null。
     */
    suspend fun removeLastExchange(sessionId: Long): RemovedExchange? {
        val recent = messageDao.getRecentBySession(sessionId, 2)
        val last = recent.firstOrNull() ?: return null
        val toDelete = when {
            last.role == MessageRole.USER -> listOf(last)
            recent.getOrNull(1)?.role == MessageRole.USER -> recent
            else -> return null
        }
        messageDao.deleteByIds(toDelete.map { it.id })
        return RemovedExchange(question = toDelete.last().content, messages = toDelete.reversed())
    }

    /**
     * 再生成で何も答えられなかったとき、[removeLastExchange] で消したやり取りを id を保ったまま戻す。
     * [requeuedQuestionId] は再生成で積み直した質問（まだ積んでいなければ null）。
     */
    suspend fun restoreExchange(removed: RemovedExchange, requeuedQuestionId: Long?) {
        requeuedQuestionId?.let { messageDao.deleteByIds(listOf(it)) }
        messageDao.insertAll(removed.messages)
    }

    suspend fun updateSessionTitle(id: Long, title: String) = sessionDao.updateTitle(id, title)

    /** 削除したセッションを返す（[restoreSession] で取り消せるように）。存在しなければ null。 */
    suspend fun deleteSession(id: Long): DeletedSession? {
        val session = sessionDao.getById(id) ?: return null
        val messages = messageDao.getAllBySession(id)
        sessionDao.deleteById(id) // CASCADE で messages も削除される
        return DeletedSession(session, messages)
    }

    /** id を保ったまま戻すので、履歴画面から開き直しても同じセッションになる。 */
    suspend fun restoreSession(deleted: DeletedSession) {
        sessionDao.insert(deleted.session)
        if (deleted.messages.isNotEmpty()) messageDao.insertAll(deleted.messages)
    }

    suspend fun clearAll() {
        sessionDao.deleteAll() // CASCADE で messages も削除される
    }
}

data class DeletedSession(
    val session: ChatSessionEntity,
    val messages: List<ChatMessageEntity>,
)

/** [ChatRepository.removeLastExchange] で消したやり取り。[messages] は古い順。 */
data class RemovedExchange(
    val question: String,
    val messages: List<ChatMessageEntity>,
)
