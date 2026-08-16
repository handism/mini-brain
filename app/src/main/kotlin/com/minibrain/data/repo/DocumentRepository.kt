package com.minibrain.data.repo

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.minibrain.ai.embed.EmbedType
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import com.minibrain.data.db.entities.FolderEmbeddingEntity
import com.minibrain.data.md.MarkdownChunker
import com.minibrain.data.md.MarkdownMetaExtractor
import com.minibrain.data.md.MdFile
import com.minibrain.data.md.MdFileReader
import org.json.JSONArray
import com.minibrain.data.search.NGramTokenizer
import com.minibrain.util.DateValidator
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

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
    private val folderEmbeddingDao: FolderEmbeddingDao,
) {
    companion object {
        private val FULL_DATE_PATTERNS = listOf(
            Regex("""(\d{4})-(\d{1,2})-(\d{1,2})"""),
            Regex("""(\d{4})/(\d{1,2})/(\d{1,2})"""),
            Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)"""),
            Regex("""(\d{4})_(\d{1,2})_(\d{1,2})"""),
            Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})"""),
            Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日"""),
        )

        private val MONTH_DATE_PATTERNS = listOf(
            Regex("""(?<!\d)(\d{4})-(\d{1,2})(?!\d|-\d)"""),
            Regex("""(\d{4})年(\d{1,2})月(?!\d)"""),
            Regex("""(?<!\d)(\d{4})(\d{2})(?!\d)"""),
        )

        // パスやファイル名から日付メタを抽出する。完全日付（日まで揃う）を先に検査し、
        // マッチしなかった場合は月単位ファイル（YYYY-MM など）を月初 1 日として扱う。
        // ユニットテストから呼び出せるよう @VisibleForTesting に昇格。
        @VisibleForTesting
        internal fun extractDateFromPath(relativePath: String): String? {
            for (pattern in FULL_DATE_PATTERNS) {
                val match = pattern.find(relativePath) ?: continue
                val (y, m, d) = match.destructured
                DateValidator.parseDay(y, m, d)?.let { return it }
            }

            for (pattern in MONTH_DATE_PATTERNS) {
                val match = pattern.find(relativePath) ?: continue
                val (y, m) = match.destructured
                DateValidator.parseMonth(y, m)?.let { return it }
            }

            return null
        }
    }

    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState

    fun observeDocCount(treeUri: String): Flow<Int> = documentDao.observeCountByTree(treeUri)
    fun observeChunkCount(treeUri: String): Flow<Int> = chunkDao.observeCountByTree(treeUri)

    suspend fun indexFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        _indexingState.value = IndexingState.Progress(0, 0, "スキャン中...")

        val mdFiles = MdFileReader.listMdFiles(context, treeUri)
        val total = mdFiles.size
        var totalChunks = 0

        // 既存のドキュメントのチャンク数をキャッシュしてN+1問題を回避する
        val existingDocs = mdFiles.map { it.uri.toString() }.chunked(900).flatMap { chunk ->
            documentDao.getByFileUris(chunk)
        }.associateBy { it.fileUri }
        val chunkCountsMap = chunkDao.getChunkCountsGroupedByDoc().associateBy({ it.docId }, { it.chunkCount })

        val writableDb = db.openHelper.writableDatabase
        val ftsSql = "INSERT OR REPLACE INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)"
        val ftsStmt = writableDb.compileStatement(ftsSql)

        try {
            val docsToUpdate = mutableListOf<DocumentEntity>()
            val docsToInsert = mutableListOf<DocumentEntity>()
            val docsToDelete = mutableListOf<Long>()

            // To maintain batching, we group the raw chunks and their metadata
            data class PendingDoc(val docEntity: DocumentEntity, val mdFile: MdFile)
            val pendingDocs = mutableListOf<PendingDoc>()

            mdFiles.forEachIndexed { index, mdFile ->
                _indexingState.value = IndexingState.Progress(index + 1, total, mdFile.name)

                val existing = existingDocs[mdFile.uri.toString()]
                var existingChunkCount = 0
                if (existing != null && existing.contentHash == mdFile.contentHash) {
                    existingChunkCount = chunkCountsMap[existing.id] ?: 0
                }

                if (existing != null && existing.contentHash == mdFile.contentHash &&
                    existingChunkCount > 0
                ) {
                    if (existing.headings == null || existing.documentDate == null) {
                        docsToUpdate.add(
                            existing.copy(
                                headings = existing.headings
                                    ?: JSONArray(MarkdownMetaExtractor.extractHeadings(mdFile.content)).toString(),
                                firstParagraph = existing.firstParagraph
                                    ?: MarkdownMetaExtractor.extractFirstParagraph(mdFile.content),
                                tags = existing.tags
                                    ?: JSONArray(MarkdownMetaExtractor.extractTags(mdFile.content)).toString(),
                                documentDate = existing.documentDate
                                    ?: extractDateFromPath(mdFile.relativePath)
                                    ?: MarkdownMetaExtractor.extractDateFromContent(mdFile.content),
                            )
                        )
                    }
                    totalChunks += existingChunkCount
                    return@forEachIndexed
                }

                if (existing != null) {
                    docsToDelete.add(existing.id)
                }

                val newDoc = DocumentEntity(
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
                    documentDate = extractDateFromPath(mdFile.relativePath)
                        ?: MarkdownMetaExtractor.extractDateFromContent(mdFile.content),
                )

                pendingDocs.add(PendingDoc(newDoc, mdFile))
            }

            // Update unchanged docs that needed metadata refresh
            if (docsToUpdate.isNotEmpty()) {
                documentDao.updateAll(docsToUpdate)
            }

            // Delete old FTS and Chunks
            if (docsToDelete.isNotEmpty()) {
                docsToDelete.chunked(900).forEach { batch ->
                    writableDb.beginTransaction()
                    try {
                        deleteFtsByDocIds(batch)
                        chunkDao.deleteByDocIds(batch)
                        writableDb.setTransactionSuccessful()
                    } finally {
                        writableDb.endTransaction()
                    }
                }
            }

            // Insert new docs
            val docsToInsertList = pendingDocs.map { it.docEntity }
            if (docsToInsertList.isNotEmpty()) {
                val insertedDocIds = documentDao.insertAll(docsToInsertList)

                val chunkBuffer = mutableListOf<ChunkEntity>()

                // Embed and collect chunks using the generated IDs
                pendingDocs.forEachIndexed { i, pending ->
                    // Emit progress state during the heavy embedding phase
                    _indexingState.value = IndexingState.Progress(i + 1, pendingDocs.size, "解析中: ${pending.mdFile.name}")

                    val docId = insertedDocIds[i]
                    val rawChunks = MarkdownChunker.chunk(pending.mdFile.content, pending.mdFile.relativePath)
                    val chunkEntities = rawChunks.mapNotNull { chunk ->
                        runCatching {
                            val embedding = embedder.embed(chunk.text, EmbedType.PASSAGE)
                            ChunkEntity(
                                docId = docId,
                                headingPath = chunk.headingPath,
                                text = chunk.text,
                                embedding = EmbedderService.floatArrayToBytes(embedding),
                            )
                        }.onFailure { e ->
                            Timber.tag("DocumentRepository").e(e, "embed failed: ${pending.mdFile.relativePath} / ${chunk.headingPath}")
                        }.getOrNull()
                    }

                    chunkBuffer.addAll(chunkEntities)

                    // Flush buffer to DB in a single SQLite transaction to avoid high memory pressure (OOM) and auto-commits
                    if (chunkBuffer.size >= 900) {
                        writableDb.beginTransaction()
                        try {
                            val chunkIds = chunkDao.insertAll(chunkBuffer)
                            insertFts(ftsStmt, chunkIds, chunkBuffer)
                            writableDb.setTransactionSuccessful()
                        } finally {
                            writableDb.endTransaction()
                        }
                        totalChunks += chunkBuffer.size
                        chunkBuffer.clear()
                    }
                }

                if (chunkBuffer.isNotEmpty()) {
                    writableDb.beginTransaction()
                    try {
                        val chunkIds = chunkDao.insertAll(chunkBuffer)
                        insertFts(ftsStmt, chunkIds, chunkBuffer)
                        writableDb.setTransactionSuccessful()
                    } finally {
                        writableDb.endTransaction()
                    }
                    totalChunks += chunkBuffer.size
                }
            }

        } finally {
            ftsStmt.close()
        }

        // フォルダ単位の仮想埋め込みを生成（直近の親フォルダでグループ化）
        indexFolderEmbeddings(treeUri, mdFiles)

        _indexingState.value = IndexingState.Done(total, totalChunks)
    }

    private suspend fun indexFolderEmbeddings(treeUri: Uri, mdFiles: List<MdFile>) {
        val byFolder = mdFiles
            .filter { it.relativePath.contains('/') }
            .groupBy { it.relativePath.substringBeforeLast('/') }

        val allFileUrisToFetch = byFolder.values.flatten().map { it.uri.toString() }
        val allDocsMap = allFileUrisToFetch.chunked(900).flatMap { chunk ->
            documentDao.getByFileUris(chunk)
        }.associateBy { it.fileUri }

        val folderEmbeddings = mutableListOf<FolderEmbeddingEntity>()

        for ((folderPath, files) in byFolder) {
            val fileUris = files.map { it.uri.toString() }
            val allDocs = fileUris.mapNotNull { allDocsMap[it] }

            val headings = allDocs.asSequence().flatMap { doc ->
                doc.headings?.let { json ->
                    runCatching {
                        val arr = org.json.JSONArray(json)
                        List(arr.length()) { i -> arr.getString(i) }
                    }.getOrElse { emptyList() }
                } ?: emptyList()
            }.take(10).toList()

            val folderText = buildString {
                append("フォルダ: $folderPath\n")
                append("ファイル: ${files.joinToString(", ") { it.name }}\n")
                if (headings.isNotEmpty()) append("見出し: ${headings.joinToString(", ")}")
            }

            runCatching {
                val embedding = embedder.embed(folderText, EmbedType.PASSAGE)
                folderEmbeddings.add(
                    FolderEmbeddingEntity(
                        path = folderPath,
                        treeUri = treeUri.toString(),
                        embedding = EmbedderService.floatArrayToBytes(embedding),
                    )
                )
            }
        }

        if (folderEmbeddings.isNotEmpty()) {
            folderEmbeddingDao.upsertAll(folderEmbeddings)
        }
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
        if (ftsCount == chunkCount) return@withContext

        val writableDb = db.openHelper.writableDatabase

        writableDb.beginTransaction()
        try {
            processChunkBatch(writableDb)
            writableDb.setTransactionSuccessful()
        } finally {
            writableDb.endTransaction()
        }
    }

    private fun processChunkBatch(writableDb: androidx.sqlite.db.SupportSQLiteDatabase) {
        // Using compileStatement provides better performance than multiple execSQL
        // Also process in batches to prevent OutOfMemory issues for large datasets
        val limit = 1000
        var lastId = 0L
        val sql = "INSERT OR REPLACE INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)"
        val stmt = writableDb.compileStatement(sql)
        try {
            while (true) {
                val chunksBatch = chunkDao.getBatchSync(lastId, limit)
                if (chunksBatch.isEmpty()) break

                chunksBatch.forEach { chunk ->
                    stmt.bindLong(1, chunk.id)
                    val textBigrams = NGramTokenizer.toBigrams(chunk.text)
                    stmt.bindString(2, textBigrams)

                    val headingBigrams = NGramTokenizer.toBigrams(chunk.headingPath)
                    stmt.bindString(3, headingBigrams)
                    stmt.executeInsert()
                    stmt.clearBindings()
                    lastId = maxOf(lastId, chunk.id)
                }
            }
        } finally {
            stmt.close()
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

    private fun insertFts(stmt: androidx.sqlite.db.SupportSQLiteStatement, ids: List<Long>, entities: List<ChunkEntity>) {
        ids.zip(entities).forEach { (id, entity) ->
            stmt.bindLong(1, id)
            val textBigrams = NGramTokenizer.toBigrams(entity.text)
            stmt.bindString(2, textBigrams)

            val headingBigrams = NGramTokenizer.toBigrams(entity.headingPath)
            stmt.bindString(3, headingBigrams)
            stmt.executeInsert()
            stmt.clearBindings()
        }
    }

    private fun deleteFtsByDocIds(docIds: List<Long>) {
        if (docIds.isEmpty()) return
        val placeholders = docIds.joinToString(",") { "?" }
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM chunks_fts WHERE rowid IN (SELECT id FROM chunks WHERE docId IN ($placeholders))",
            docIds.toTypedArray()
        )
    }

    private fun extractDateFromPath(relativePath: String): String? =
        Companion.extractDateFromPath(relativePath)

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
