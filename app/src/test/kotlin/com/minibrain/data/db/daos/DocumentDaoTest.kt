package com.minibrain.data.db.daos

import com.minibrain.data.db.entities.DocumentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentDaoTest {

    @Test
    fun searchByPath_escapesSpecialCharactersCorrectly() = runBlocking {
        var capturedKeyword: String? = null

        val fakeDao = object : DocumentDao {
            override suspend fun getAllByTree(treeUri: String): List<DocumentEntity> = emptyList()
            override fun observeAllByTree(treeUri: String) = throw UnsupportedOperationException()
            override fun observeCountByTree(treeUri: String) = throw UnsupportedOperationException()
            override suspend fun getByFileUri(fileUri: String): DocumentEntity? = null
            override suspend fun getByFileUris(fileUris: List<String>): List<DocumentEntity> = emptyList()
            override suspend fun insert(doc: DocumentEntity): Long = 0
            override suspend fun insertAll(docs: List<DocumentEntity>): List<Long> = emptyList()
            override suspend fun update(doc: DocumentEntity) {}
            override suspend fun updateAll(docs: List<DocumentEntity>) {}
            override suspend fun deleteAllByTree(treeUri: String) {}
            override suspend fun deleteByFileUri(fileUri: String) {}
            override suspend fun getById(id: Long): DocumentEntity? = null
            override suspend fun _searchByPath(treeUri: String, keyword: String): List<DocumentEntity> {
                capturedKeyword = keyword
                return emptyList()
            }
            override suspend fun getRecentFiles(treeUri: String, limit: Int): List<DocumentEntity> = emptyList()
            override suspend fun getDocDatesByIds(ids: List<Long>): List<DocDateRow> = emptyList()
            override suspend fun getDocPathsByIds(ids: List<Long>): List<DocPathRow> = emptyList()
            override suspend fun getByDateRange(treeUri: String, start: String, end: String): List<DocumentEntity> = emptyList()
            override suspend fun findByMetadataRaw(query: androidx.sqlite.db.SupportSQLiteQuery): List<DocumentEntity> = emptyList()
            override suspend fun getMinimalByTree(treeUri: String): List<DocumentMinimal> = emptyList()
        }

        fakeDao.searchByPath("uri", "100%_test\\file")

        assertEquals("100\\%\\_test\\\\file", capturedKeyword)
    }
}
