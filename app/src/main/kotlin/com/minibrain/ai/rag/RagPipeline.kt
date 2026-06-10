package com.minibrain.ai.rag

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

enum class SourceType { READ_FILE, GREP, VECTOR, RRF, GLOB, FOLDER, UNKNOWN }

data class Citation(
    val headingPath: String,
    val snippet: String,
    val score: Float = 0f,
    val docId: Long? = null,
    val relativePath: String? = null,
    val source: SourceType = SourceType.UNKNOWN,
)

class RagPipeline(
    private val embedderService: EmbedderService,
    private val llmService: LlmService,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
    private val folderEmbeddingDao: FolderEmbeddingDao,
) {
    suspend fun retrieveTopChunks(question: String, treeUri: String? = null, topK: Int = 20): List<Citation> =
        coroutineScope {
            val vecJob = async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { vectorSearch(question, treeUri, k = 50) }
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
            val docIdToDate: Map<Long, LocalDate?> = withContext(Dispatchers.IO) {
                documentDao.getDocDatesByIds(allDocIds)
            }.associate { row ->
                row.id to row.documentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            }

            val docCache = mutableMapOf<Long, String?>()
            suspend fun relativePath(docId: Long): String? =
                docCache.getOrPut(docId) { documentDao.getById(docId)?.relativePath }

            val chunkCitations = rrf(bm25Results, vecResults.map { it.second }, topK, docIdToDate = docIdToDate)
                .map { (score, chunk) ->
                    Log.d("RagPipeline", "rrf=%.4f path=${chunk.headingPath}".format(score))
                    Citation(
                        headingPath = chunk.headingPath,
                        snippet = chunk.text,
                        score = score,
                        docId = chunk.docId,
                        relativePath = relativePath(chunk.docId),
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

    fun answer(
        question: String,
        citations: List<Citation>,
        recentHistory: List<Pair<String, String>> = emptyList(),
    ): Flow<String> {
        val prompt = buildPrompt(question, citations, recentHistory)
        return llmService.generateStream(prompt)
    }

    private suspend fun vectorSearch(question: String, treeUri: String?, k: Int): List<Pair<Float, ChunkEntity>> =
        withContext(Dispatchers.Default) {
            val queryVec = embedderService.embed(question)
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

    private suspend fun folderSearch(question: String, treeUri: String?, k: Int): List<Pair<Float, FolderEmbeddingEntity>> =
        withContext(Dispatchers.Default) {
            val queryVec = embedderService.embed(question)
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
        val scores = mutableMapOf<Long, Float>()
        val chunks = mutableMapOf<Long, ChunkEntity>()

        bm25Results.forEachIndexed { rank, chunk ->
            scores[chunk.id] = (scores[chunk.id] ?: 0f) + 1f / (k + rank + 1)
            chunks[chunk.id] = chunk
        }
        vecResults.forEachIndexed { rank, chunk ->
            scores[chunk.id] = (scores[chunk.id] ?: 0f) + 1f / (k + rank + 1)
            chunks[chunk.id] = chunk
        }

        val today = LocalDate.now()
        return scores.entries
            .map { (id, rrfScore) ->
                val boost = freshnessBoost(docIdToDate[chunks[id]!!.docId], today)
                Triple(id, rrfScore + boost, chunks[id]!!)
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (_, score, chunk) -> Pair(score, chunk) }
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

    private fun buildPrompt(
        question: String,
        citations: List<Citation>,
        history: List<Pair<String, String>>,
    ): String {
        val contextBlock = if (citations.isNotEmpty()) {
            val citationText = citations.joinToString("\n\n") { c ->
                "### ${c.headingPath}\n${c.snippet}"
            }
            """
あなたはユーザーのパーソナルアシスタントです。以下の「知識ベース（プライベートメモ）」の内容を参考にして、質問に答えてください。
回答は、知識ベースにある情報を優先的に使用してください。もし情報が不足している場合は、一般的な知識で補足しても構いませんが、その際は知識ベース外の情報であることを明記してください。

知識ベースの内容:
$citationText

---
""".trimIndent()
        } else {
            "知識ベースに関連する情報が見つかりませんでした。一般的な知識で回答してください。\n\n---"
        }

        val historyBlock = if (history.isNotEmpty()) {
            history.takeLast(6).joinToString("\n") { (role, content) ->
                "${if (role == "user") "ユーザー" else "アシスタント"}: $content"
            } + "\n"
        } else ""

        return """
$contextBlock

$historyBlock
ユーザー: $question
アシスタント:""".trimStart()
    }
}
