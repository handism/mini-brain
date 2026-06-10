package com.minibrain.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folder_embeddings",
    indices = [Index(value = ["path", "treeUri"], unique = true)],
)
data class FolderEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val treeUri: String,
    val embedding: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FolderEmbeddingEntity) return false
        return id == other.id && path == other.path && treeUri == other.treeUri
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + path.hashCode()) + treeUri.hashCode()
}
