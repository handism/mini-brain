package com.minibrain.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.minibrain.data.db.entities.ChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("SELECT * FROM chunks WHERE docId = :docId")
    suspend fun getByDoc(docId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks")
    suspend fun getAll(): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks INNER JOIN documents ON chunks.docId = documents.id WHERE documents.treeUri = :treeUri")
    fun observeCountByTree(treeUri: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun count(): Int

    @Query("DELETE FROM chunks WHERE docId = :docId")
    suspend fun deleteByDoc(docId: Long)

    @Query("DELETE FROM chunks WHERE docId IN (SELECT id FROM documents WHERE treeUri = :treeUri)")
    suspend fun deleteAllByTree(treeUri: String)

    @RawQuery
    suspend fun bm25SearchRaw(query: SupportSQLiteQuery): List<ChunkEntity>
}
