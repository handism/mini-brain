package com.minibrain.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.db.entities.ChatSessionSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert
    suspend fun insert(session: ChatSessionEntity): Long

    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChatSessionEntity>>

    // 履歴一覧は「最後にやり取りした日時」順に並べる（メッセージが無いセッションは作成日時）
    @Query(
        """
        SELECT s.id, s.title, s.createdAt,
               COALESCE(MAX(m.createdAt), s.createdAt) AS updatedAt,
               COUNT(m.id) AS messageCount,
               (SELECT a.content FROM chat_messages a
                WHERE a.sessionId = s.id AND a.role = 'ASSISTANT'
                ORDER BY a.createdAt DESC, a.id DESC LIMIT 1) AS lastAnswer
        FROM chat_sessions s
        LEFT JOIN chat_messages m ON m.sessionId = s.id
        GROUP BY s.id
        ORDER BY updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<ChatSessionSummary>>

    @Query("SELECT title FROM chat_sessions WHERE id = :id")
    fun observeTitle(id: Long): Flow<String?>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()
}
