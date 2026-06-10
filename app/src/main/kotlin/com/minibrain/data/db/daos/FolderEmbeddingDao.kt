package com.minibrain.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.minibrain.data.db.entities.FolderEmbeddingEntity

@Dao
interface FolderEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderEmbeddingEntity)

    @Query("SELECT * FROM folder_embeddings WHERE treeUri = :treeUri")
    suspend fun getAllByTree(treeUri: String): List<FolderEmbeddingEntity>

    @Query("DELETE FROM folder_embeddings WHERE treeUri = :treeUri")
    suspend fun deleteAllByTree(treeUri: String)
}
