package com.minibrain.data.db.entities

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "chunks_fts")
data class ChunkFtsEntity(
    val text_bigram: String,
    val heading_bigram: String
)
