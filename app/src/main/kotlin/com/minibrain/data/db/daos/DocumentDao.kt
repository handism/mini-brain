package com.minibrain.data.db.daos

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.minibrain.data.db.entities.DocumentEntity
import kotlinx.coroutines.flow.Flow

data class DocDateRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "documentDate") val documentDate: String?,
)

data class DocumentMinimal(
    val id: Long,
    val fileName: String,
    val relativePath: String,
    @ColumnInfo(name = "first_para") val firstParagraph: String?,
    val documentDate: String?,
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE treeUri = :treeUri")
    suspend fun getAllByTree(treeUri: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE treeUri = :treeUri")
    fun observeAllByTree(treeUri: String): Flow<List<DocumentEntity>>

    @Query("SELECT COUNT(*) FROM documents WHERE treeUri = :treeUri")
    fun observeCountByTree(treeUri: String): Flow<Int>

    @Query("SELECT * FROM documents WHERE fileUri = :fileUri LIMIT 1")
    suspend fun getByFileUri(fileUri: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE treeUri = :treeUri")
    suspend fun deleteAllByTree(treeUri: String)

    @Query("DELETE FROM documents WHERE fileUri = :fileUri")
    suspend fun deleteByFileUri(fileUri: String)

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE treeUri = :treeUri AND relativePath LIKE '%' || :keyword || '%'")
    suspend fun searchByPath(treeUri: String, keyword: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE treeUri = :treeUri ORDER BY lastModified DESC LIMIT :limit")
    suspend fun getRecentFiles(treeUri: String, limit: Int): List<DocumentEntity>

    @Query("SELECT id, documentDate FROM documents WHERE id IN (:ids)")
    suspend fun getDocDatesByIds(ids: List<Long>): List<DocDateRow>

    @Query("SELECT * FROM documents WHERE treeUri = :treeUri AND documentDate >= :start AND documentDate <= :end ORDER BY documentDate ASC")
    suspend fun getByDateRange(treeUri: String, start: String, end: String): List<DocumentEntity>

    @androidx.room.RawQuery
    suspend fun findByMetadataRaw(query: androidx.sqlite.db.SupportSQLiteQuery): List<DocumentEntity>

    @Query("SELECT id, fileName, relativePath, first_para, documentDate FROM documents WHERE treeUri = :treeUri")
    suspend fun getMinimalByTree(treeUri: String): List<DocumentMinimal>
}
