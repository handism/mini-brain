package com.minibrain.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.minibrain.data.db.entities.FolderEmbeddingEntity

@Dao
interface FolderEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FolderEmbeddingEntity>)

    @Query("SELECT * FROM folder_embeddings WHERE treeUri = :treeUri")
    suspend fun getAllByTree(treeUri: String): List<FolderEmbeddingEntity>

    @Query("DELETE FROM folder_embeddings WHERE treeUri = :treeUri")
    suspend fun deleteAllByTree(treeUri: String)

    /** tree 配下のフォルダ埋め込みを丸ごと入れ替える。消えたフォルダの行を残さないため。 */
    @Transaction
    suspend fun replaceAllByTree(treeUri: String, entities: List<FolderEmbeddingEntity>) {
        deleteAllByTree(treeUri)
        if (entities.isNotEmpty()) upsertAll(entities)
    }
}
