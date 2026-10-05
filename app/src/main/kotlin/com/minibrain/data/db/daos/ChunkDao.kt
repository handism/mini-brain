package com.minibrain.data.db.daos

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.search.Bm25Scorer
import com.minibrain.data.search.FtsMatchInfo
import kotlinx.coroutines.flow.Flow

data class DocChunkCount(
    @ColumnInfo(name = "docId") val docId: Long,
    @ColumnInfo(name = "chunkCount") val chunkCount: Int
)

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY id")
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
        ORDER BY chunks.id
    """)
    suspend fun getAllByTree(treeUri: String): List<ChunkEntity>

    @Query("""
        SELECT chunks.* FROM chunks 
        INNER JOIN documents ON chunks.docId = documents.id 
        WHERE documents.treeUri = :treeUri AND documents.relativePath LIKE :scope || '%' ESCAPE '\'
        ORDER BY chunks.id
    """)
    suspend fun _getByScope(treeUri: String, scope: String): List<ChunkEntity>

    suspend fun getByScope(treeUri: String, scope: String): List<ChunkEntity> {
        val escapedScope = com.minibrain.data.db.util.SqlUtils.escapeForLike(scope)
        return _getByScope(treeUri, escapedScope)
    }

    @Query("SELECT COUNT(*) FROM chunks INNER JOIN documents ON chunks.docId = documents.id WHERE documents.treeUri = :treeUri")
    fun observeCountByTree(treeUri: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun count(): Int

    @Query("DELETE FROM chunks WHERE docId = :docId")
    suspend fun deleteByDoc(docId: Long)

    @Query("DELETE FROM chunks WHERE docId IN (:docIds)")
    suspend fun deleteByDocIds(docIds: List<Long>)

    @Query("DELETE FROM chunks_fts WHERE rowid IN (SELECT id FROM chunks WHERE docId IN (:docIds))")
    suspend fun deleteFtsByDocIds(docIds: List<Long>)

    @Query("DELETE FROM chunks WHERE docId IN (SELECT id FROM documents WHERE treeUri = :treeUri)")
    suspend fun deleteAllByTree(treeUri: String)

    @Query("""
        DELETE FROM chunks_fts WHERE rowid IN (
            SELECT chunks.id FROM chunks
            INNER JOIN documents ON chunks.docId = documents.id
            WHERE documents.treeUri = :treeUri
        )
    """)
    suspend fun deleteFtsByTree(treeUri: String)

    // matchinfo は FTS の MATCH を直接引くクエリでしか使えないので、サブクエリ側で取る
    @Query("""
        SELECT fts.rowid AS chunkId, fts.info AS info FROM (
            SELECT rowid, matchinfo(chunks_fts, 'pcnalx') AS info FROM chunks_fts WHERE chunks_fts MATCH :matchQuery
        ) AS fts
        JOIN chunks ON chunks.id = fts.rowid
        JOIN documents ON chunks.docId = documents.id
        WHERE documents.treeUri = :treeUri
    """)
    suspend fun _ftsMatchInfoByTree(matchQuery: String, treeUri: String): List<FtsMatchInfo>

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun _getByIds(ids: List<Long>): List<ChunkEntity>

    /**
     * MATCH した chunk を BM25 の高い順に最大 limit 件返す（ADR-040）。
     * FTS4 は順位を付けないため、全ヒットの matchinfo を取って Kotlin 側で採点する。個人用途の規模（数千チャンク）が前提。
     */
    suspend fun bm25SearchByTree(matchQuery: String, treeUri: String, limit: Int): List<ChunkEntity> {
        val ids = Bm25Scorer.rank(_ftsMatchInfoByTree(matchQuery, treeUri), limit)
        if (ids.isEmpty()) return emptyList()
        val byId = _getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}
