package com.minibrain.data.repo

import android.content.Context
import android.net.Uri
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import com.minibrain.data.md.MarkdownChunker
import com.minibrain.data.md.MarkdownMetaExtractor
import com.minibrain.data.md.MdFileReader
import org.json.JSONArray
import com.minibrain.data.search.NGramTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

sealed class IndexingState {
    object Idle : IndexingState()
    data class Progress(val current: Int, val total: Int, val fileName: String) : IndexingState()
    data class Done(val fileCount: Int, val chunkCount: Int) : IndexingState()
    data class Error(val message: String) : IndexingState()
}

class DocumentRepository(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val chunkDao: ChunkDao,
    private val embedder: EmbedderService,
    private val db: AppDatabase,
) {
    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState

    fun observeDocCount(treeUri: String): Flow<Int> = documentDao.observeCountByTree(treeUri)
    fun observeChunkCount(treeUri: String): Flow<Int> = chunkDao.observeCountByTree(treeUri)

    suspend fun indexFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        _indexingState.value = IndexingState.Progress(0, 0, "スキャン中...")

        val mdFiles = MdFileReader.listMdFiles(context, treeUri)
        val total = mdFiles.size
        var totalChunks = 0

        mdFiles.forEachIndexed { index, mdFile ->
            _indexingState.value = IndexingState.Progress(index + 1, total, mdFile.name)

            val existing = documentDao.getByFileUri(mdFile.uri.toString())
            if (existing != null && existing.contentHash == mdFile.contentHash) {
                if (existing.headings == null) {
                    documentDao.update(
                        existing.copy(
                            headings = JSONArray(MarkdownMetaExtractor.extractHeadings(mdFile.content)).toString(),
                            firstParagraph = MarkdownMetaExtractor.extractFirstParagraph(mdFile.content),
                            tags = JSONArray(MarkdownMetaExtractor.extractTags(mdFile.content)).toString(),
                        )
                    )
                }
                totalChunks += chunkDao.getByDoc(existing.id).size
                return@forEachIndexed
            }

            if (existing != null) {
                deleteFtsByDoc(existing.id)
                chunkDao.deleteByDoc(existing.id)
            }

            val docId = documentDao.insert(
                DocumentEntity(
                    id = existing?.id ?: 0,
                    treeUri = treeUri.toString(),
                    fileUri = mdFile.uri.toString(),
                    fileName = mdFile.name,
                    relativePath = mdFile.relativePath,
                    lastModified = mdFile.lastModified,
                    contentHash = mdFile.contentHash,
                    headings = JSONArray(MarkdownMetaExtractor.extractHeadings(mdFile.content)).toString(),
                    firstParagraph = MarkdownMetaExtractor.extractFirstParagraph(mdFile.content),
                    tags = JSONArray(MarkdownMetaExtractor.extractTags(mdFile.content)).toString(),
                )
            )

            val rawChunks = MarkdownChunker.chunk(mdFile.content, mdFile.relativePath)
            val chunkEntities = rawChunks.mapNotNull { chunk ->
                runCatching {
                    val embedding = embedder.embed(chunk.text)
                    ChunkEntity(
                        docId = docId,
                        headingPath = chunk.headingPath,
                        text = chunk.text,
                        embedding = EmbedderService.floatArrayToBytes(embedding),
                    )
                }.getOrNull()
            }

            val ids = chunkDao.insertAll(chunkEntities)
            insertFts(ids, chunkEntities)
            totalChunks += chunkEntities.size
        }

        _indexingState.value = IndexingState.Done(total, totalChunks)
    }

    suspend fun clearFolder(treeUri: String) = withContext(Dispatchers.IO) {
        deleteFtsByTree(treeUri)
        chunkDao.deleteAllByTree(treeUri)
        documentDao.deleteAllByTree(treeUri)
        _indexingState.value = IndexingState.Idle
    }

    /** 起動時に FTS インデックスが不完全であれば全チャンクを再投入する。 */
    suspend fun ensureFtsIndex() = withContext(Dispatchers.IO) {
        val chunkCount = chunkDao.count()
        val ftsCount = ftsCount()
        if (ftsCount >= chunkCount) return@withContext

        val allChunks = chunkDao.getAll()
        val writableDb = db.openHelper.writableDatabase

        writableDb.beginTransaction()
        try {
            allChunks.forEach { chunk ->
                writableDb.execSQL(
                    "INSERT OR REPLACE INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)",
                    arrayOf<Any?>(chunk.id, NGramTokenizer.toBigrams(chunk.text), NGramTokenizer.toBigrams(chunk.headingPath))
                )
            }
            writableDb.setTransactionSuccessful()
        } finally {
            writableDb.endTransaction()
        }
    }

    private fun ftsCount(): Int {
        val cursor = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM chunks_fts", emptyArray<Any?>())
        return try {
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    private fun insertFts(ids: List<Long>, entities: List<ChunkEntity>) {
        val writableDb = db.openHelper.writableDatabase
        ids.zip(entities).forEach { (id, entity) ->
            writableDb.execSQL(
                "INSERT OR REPLACE INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)",
                arrayOf<Any?>(id, NGramTokenizer.toBigrams(entity.text), NGramTokenizer.toBigrams(entity.headingPath))
            )
        }
    }

    private fun deleteFtsByDoc(docId: Long) {
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM chunks_fts WHERE rowid IN (SELECT id FROM chunks WHERE docId = ?)",
            arrayOf(docId)
        )
    }

    private fun deleteFtsByTree(treeUri: String) {
        db.openHelper.writableDatabase.execSQL(
            """DELETE FROM chunks_fts WHERE rowid IN (
                SELECT chunks.id FROM chunks
                INNER JOIN documents ON chunks.docId = documents.id
                WHERE documents.treeUri = ?
            )""",
            arrayOf(treeUri)
        )
    }
}
