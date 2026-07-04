package com.minibrain.data.db.daos

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.minibrain.data.db.entities.ChunkEntity
import kotlinx.coroutines.flow.Flow

data class DocChunkCount(
    @ColumnInfo(name = "docId") val docId: Long,
    @ColumnInfo(name = "chunkCount") val chunkCount: Int
)

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("SELECT * FROM chunks WHERE docId = :docId")
    suspend fun getByDoc(docId: Long): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE docId = :docId")
    suspend fun countByDoc(docId: Long): Int

    @Query("SELECT docId, COUNT(*) as chunkCount FROM chunks GROUP BY docId")
    suspend fun getChunkCountsGroupedByDoc(): List<DocChunkCount>

    @Query("SELECT * FROM chunks")
    suspend fun getAll(): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    fun getBatchSync(lastId: Long, limit: Int): List<ChunkEntity>

    @Query("""
        SELECT chunks.* FROM chunks 
        INNER JOIN documents ON chunks.docId = documents.id 
        WHERE documents.treeUri = :treeUri
    """)
    suspend fun getAllByTree(treeUri: String): List<ChunkEntity>

    @Query("""
        SELECT chunks.* FROM chunks 
        INNER JOIN documents ON chunks.docId = documents.id 
        WHERE documents.treeUri = :treeUri AND documents.relativePath LIKE :scope || '%'
    """)
    suspend fun getByScope(treeUri: String, scope: String): List<ChunkEntity>

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
