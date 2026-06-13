package com.minibrain.ai.rag

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.embed.EmbedType
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.FolderEmbeddingEntity
import com.minibrain.data.search.NGramTokenizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

enum class SourceType { READ_FILE, GREP, METADATA, BM25, VECTOR, RRF, GLOB, FOLDER, UNKNOWN }

data class Citation(
    val headingPath: String,
    val snippet: String,
    val score: Float = 0f,
    val docId: Long? = null,
    val relativePath: String? = null,
    val source: SourceType = SourceType.UNKNOWN,
    // ファイル名が質問の substring として一致したことを示すフラグ。
    // 「サウナしきじにいつ行ったっけ」のような固有名詞ヒットを、後段（Reranker / CoverageChecker /
    // AnswerPrompt）が documentDate に頼らず最優先で残せるようにする（ADR-026）。
    val topicMatch: Boolean = false,
)

class RagPipeline(
    private val embedderService: EmbedderService,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
    private val folderEmbeddingDao: FolderEmbeddingDao,
) {
    suspend fun retrieveTopChunks(
        question: String,
        treeUri: String? = null,
        topK: Int = 20,
        cache: SearchRequestCache? = null,
    ): List<Citation> =
        coroutineScope {
            val vecJob = async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { vectorSearch(question, treeUri, k = 50, cache) }
                    ?: run { Log.w("RagPipeline", "vectorSearch timed out"); emptyList() }
            }
            val bm25Job = async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { bm25Search(question, treeUri, k = 50) }
                    ?: run { Log.w("RagPipeline", "bm25Search timed out"); emptyList() }
            }
            val folderJob = async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { folderSearch(question, treeUri, k = 5) }
                    ?: run { Log.w("RagPipeline", "folderSearch timed out"); emptyList() }
            }

            val vecResults = vecJob.await()
            val bm25Results = bm25Job.await()
            val folderResults = folderJob.await()

            Log.d("RagPipeline", "vec=${vecResults.size} bm25=${bm25Results.size} folder=${folderResults.size}")

            val allDocIds = (vecResults.map { it.second.docId } + bm25Results.map { it.docId }).distinct()
            val docIdToDate: Map<Long, LocalDate?> = if (cache != null) {
                cache.documents().associate { doc ->
                    doc.id to doc.documentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                }
            } else {
                withContext(Dispatchers.IO) {
                    documentDao.getDocDatesByIds(allDocIds)
                }.associate { row ->
                    row.id to row.documentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                }
            }

            val docPathMap = resolveDocPaths(allDocIds, cache)

            val chunkCitations = rrf(bm25Results, vecResults.map { it.second }, topK, docIdToDate = docIdToDate)
                .map { (score, chunk) ->
                    Log.d("RagPipeline", "rrf=%.4f path=${chunk.headingPath}".format(score))
                    Citation(
                        headingPath = chunk.headingPath,
                        snippet = chunk.text,
                        score = score,
                        docId = chunk.docId,
                        relativePath = docPathMap[chunk.docId],
                        source = SourceType.RRF,
                    )
                }

            val folderCitations = folderResults.map { (score, fe) ->
                Citation(
                    headingPath = "フォルダ: ${fe.path}",
                    snippet = "（フォルダ全体に関連するコンテンツ）",
                    score = score,
                    docId = null,
                    relativePath = fe.path,
                    source = SourceType.FOLDER,
                )
            }

            chunkCitations + folderCitations
        }

    // ベクトル検索のみで Citation 化（SearchPipeline の Parallel Retrieval から呼ぶ）
    suspend fun vectorOnlyTopK(
        question: String,
        treeUri: String,
        k: Int = 20,
        cache: SearchRequestCache? = null,
    ): List<Citation> {
        val hits = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { vectorSearch(question, treeUri, k, cache) }
            ?: run { Log.w("RagPipeline", "vectorOnlyTopK timed out"); return emptyList() }
        val docPathMap = resolveDocPaths(hits.map { it.second.docId }, cache)
        return hits.map { (score, chunk) ->
            Citation(
                headingPath = chunk.headingPath,
                snippet = chunk.text,
                score = score,
                docId = chunk.docId,
                relativePath = docPathMap[chunk.docId],
                source = SourceType.VECTOR,
            )
        }
    }

    private suspend fun vectorSearch(
        question: String,
        treeUri: String?,
        k: Int,
        cache: SearchRequestCache? = null,
    ): List<Pair<Float, ChunkEntity>> =
        withContext(Dispatchers.Default) {
            val queryVec = embedderService.embed(question, EmbedType.QUERY)
            // 同一 treeUri のキャッシュがあればロード+デコード済みベクトルを再利用する
            if (cache != null && treeUri != null && cache.treeUri == treeUri) {
                return@withContext cache.cosineTopK(queryVec, k)
            }
            val chunks = if (treeUri != null) {
                chunkDao.getAllByTree(treeUri)
            } else {
                chunkDao.getAll()
            }
            val candidates = chunks.map { chunk ->
                Pair(EmbedderService.bytesToFloatArray(chunk.embedding), chunk)
            }
            @Suppress("UNCHECKED_CAST")
            CosineSimilarity.topK(queryVec, candidates as List<Pair<FloatArray, Any>>, k)
                .map { (score, meta) -> Pair(score, meta as ChunkEntity) }
        }

    private suspend fun resolveDocPaths(
        docIds: List<Long>,
        cache: SearchRequestCache?,
    ): Map<Long, String?> {
        if (cache != null) {
            val byId = cache.documents().associateBy { it.id }
            return docIds.distinct().associateWith { byId[it]?.relativePath }
        }
        val out = mutableMapOf<Long, String?>()
        for (id in docIds.distinct()) {
            out[id] = documentDao.getById(id)?.relativePath
        }
        return out
    }

    private suspend fun folderSearch(question: String, treeUri: String?, k: Int): List<Pair<Float, FolderEmbeddingEntity>> =
        withContext(Dispatchers.Default) {
            val queryVec = embedderService.embed(question, EmbedType.QUERY)
            val folders = withContext(Dispatchers.IO) {
                if (treeUri != null) folderEmbeddingDao.getAllByTree(treeUri)
                else emptyList()
            }
            if (folders.isEmpty()) return@withContext emptyList()
            val candidates = folders.map { fe ->
                Pair(EmbedderService.bytesToFloatArray(fe.embedding), fe)
            }
            @Suppress("UNCHECKED_CAST")
            CosineSimilarity.topK(queryVec, candidates as List<Pair<FloatArray, Any>>, k)
                .map { (score, meta) -> Pair(score, meta as FolderEmbeddingEntity) }
        }

    private suspend fun bm25Search(question: String, treeUri: String?, k: Int): List<ChunkEntity> {
        val matchQuery = NGramTokenizer.toFtsMatchQuery(question) ?: return emptyList()
        val sql = if (treeUri != null) {
            """SELECT chunks.* FROM chunks_fts
               JOIN chunks ON chunks_fts.rowid = chunks.id
               JOIN documents ON chunks.docId = documents.id
               WHERE chunks_fts MATCH ? AND documents.treeUri = ?
               LIMIT ?"""
        } else {
            """SELECT chunks.* FROM chunks_fts
               JOIN chunks ON chunks_fts.rowid = chunks.id
               WHERE chunks_fts MATCH ?
               LIMIT ?"""
        }
        val args: Array<Any?> = if (treeUri != null) arrayOf(matchQuery, treeUri, k) else arrayOf(matchQuery, k)
        
        return runCatching {
            chunkDao.bm25SearchRaw(SimpleSQLiteQuery(sql, args))
        }.getOrElse { e ->
            Log.w("RagPipeline", "BM25 search failed: ${e.message}")
            emptyList()
        }
    }

    private fun rrf(
        bm25Results: List<ChunkEntity>,
        vecResults: List<ChunkEntity>,
        topK: Int,
        k: Int = 60,
        docIdToDate: Map<Long, LocalDate?> = emptyMap(),
    ): List<Pair<Float, ChunkEntity>> {
        val fused = RrfFuser.fuse(
            rankLists = listOf(bm25Results, vecResults),
            keyOf = { it.id },
            k = k,
        )
        val today = LocalDate.now()
        return fused
            .map { entry ->
                val boost = freshnessBoost(docIdToDate[entry.item.docId], today)
                Pair(entry.score + boost, entry.item)
            }
            .sortedByDescending { it.first }
            .take(topK)
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 8_000L
        // freshnessBoost tuning constants — adjust to balance recency vs. relevance
        // RRF max score ≈ 0.032 (rank=1 in both BM25 and vector)
        private const val FRESHNESS_BOOST_MAX  = 0.010f  // 最大加点 (RRF max の約 30%)
        private const val FRESHNESS_DECAY_DAYS = 90f     // 半減期 90 日 (30d:~0.0072, 1y:~0.0017, 3y:~0.0001)

        fun freshnessBoost(docDate: LocalDate?, today: LocalDate): Float {
            if (docDate == null) return 0f
            val days = ChronoUnit.DAYS.between(docDate, today).coerceAtLeast(0).toFloat()
            return (FRESHNESS_BOOST_MAX * exp(-days / FRESHNESS_DECAY_DAYS)).toFloat()
        }
    }
}
