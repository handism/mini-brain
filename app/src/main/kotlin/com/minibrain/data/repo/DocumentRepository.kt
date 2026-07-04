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
        // パスやファイル名から日付メタを抽出する。完全日付（日まで揃う）を先に検査し、
        // マッチしなかった場合は月単位ファイル（YYYY-MM など）を月初 1 日として扱う。
        // ユニットテストから呼び出せるよう @VisibleForTesting に昇格。
        @VisibleForTesting
        internal fun extractDateFromPath(relativePath: String): String? {
            val yearRange = 1990..LocalDate.now().year

            val fullPatterns = listOf(
                Regex("""(\d{4})-(\d{1,2})-(\d{1,2})"""),
                Regex("""(\d{4})/(\d{1,2})/(\d{1,2})"""),
                Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)"""),
                Regex("""(\d{4})_(\d{1,2})_(\d{1,2})"""),
                Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})"""),
                Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日"""),
            )
            for (pattern in fullPatterns) {
                val match = pattern.find(relativePath) ?: continue
                val (y, m, d) = match.destructured
                val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
                if (date != null && date.year in yearRange) return date.toString()
            }

            val monthPatterns = listOf(
                Regex("""(?<!\d)(\d{4})-(\d{1,2})(?!\d|-\d)"""),
                Regex("""(\d{4})年(\d{1,2})月(?!\d)"""),
                Regex("""(?<!\d)(\d{4})(\d{2})(?!\d)"""),
            )
            for (pattern in monthPatterns) {
                val match = pattern.find(relativePath) ?: continue
                val (y, m) = match.destructured
                val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), 1) }.getOrNull()
                if (date != null && date.year in yearRange) return date.toString()
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

        mdFiles.forEachIndexed { index, mdFile ->
            _indexingState.value = IndexingState.Progress(index + 1, total, mdFile.name)

            val existing = documentDao.getByFileUri(mdFile.uri.toString())
            // チャンク 0 件は過去のインデックスで embed が全滅した痕跡なので、ハッシュ一致でも再処理する
            if (existing != null && existing.contentHash == mdFile.contentHash &&
                chunkDao.getByDoc(existing.id).isNotEmpty()
            ) {
                if (existing.headings == null || existing.documentDate == null) {
                    documentDao.update(
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
                    documentDate = extractDateFromPath(mdFile.relativePath)
                        ?: MarkdownMetaExtractor.extractDateFromContent(mdFile.content),
                )
            )

            val rawChunks = MarkdownChunker.chunk(mdFile.content, mdFile.relativePath)
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
                    Timber.tag("DocumentRepository").e(e, "embed failed: ${mdFile.relativePath} / ${chunk.headingPath}")
                }.getOrNull()
            }

            val ids = chunkDao.insertAll(chunkEntities)
            insertFts(ids, chunkEntities)
            totalChunks += chunkEntities.size
        }

        // フォルダ単位の仮想埋め込みを生成（直近の親フォルダでグループ化）
        indexFolderEmbeddings(treeUri, mdFiles)

        _indexingState.value = IndexingState.Done(total, totalChunks)
    }

    private suspend fun indexFolderEmbeddings(treeUri: Uri, mdFiles: List<MdFile>) {
        val byFolder = mdFiles
            .filter { it.relativePath.contains('/') }
            .groupBy { it.relativePath.substringBeforeLast('/') }

        for ((folderPath, files) in byFolder) {
            val allDocs = files.mapNotNull { f ->
                documentDao.getByFileUri(f.uri.toString())
            }
            val headings = allDocs.flatMap { doc ->
                doc.headings?.let { json ->
                    runCatching {
                        val arr = org.json.JSONArray(json)
                        (0 until arr.length()).map { i -> arr.getString(i) }
                    }.getOrElse { emptyList() }
                } ?: emptyList()
            }.take(10)

            val folderText = buildString {
                append("フォルダ: $folderPath\n")
                append("ファイル: ${files.joinToString(", ") { it.name }}\n")
                if (headings.isNotEmpty()) append("見出し: ${headings.joinToString(", ")}")
            }

            runCatching {
                val embedding = embedder.embed(folderText, EmbedType.PASSAGE)
                folderEmbeddingDao.upsert(
                    FolderEmbeddingEntity(
                        path = folderPath,
                        treeUri = treeUri.toString(),
                        embedding = EmbedderService.floatArrayToBytes(embedding),
                    )
                )
            }
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
