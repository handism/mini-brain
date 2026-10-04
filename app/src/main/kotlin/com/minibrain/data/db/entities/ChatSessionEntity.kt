package com.minibrain.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 履歴一覧用。[updatedAt] はセッション内の最新メッセージの日時。
 * [lastAnswer] は最新の回答（一覧で中身を見分けるため）。回答がまだ無ければ null。
 */
data class ChatSessionSummary(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val lastAnswer: String? = null,
)
