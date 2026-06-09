package com.minibrain.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.minibrain.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert
    suspend fun insert(session: ChatSessionEntity): Long

    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()
}
