package com.minibrain.ai.rag

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.search.NGramTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

enum class SourceType { READ_FILE, GREP, VECTOR, RRF, GLOB, UNKNOWN }

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
) {
    suspend fun retrieveTopChunks(question: String, treeUri: String? = null, topK: Int = 20): List<Citation> =
        coroutineScope {
            val vecJob = async { vectorSearch(question, treeUri, k = 50) }
            val bm25Job = async { bm25Search(question, treeUri, k = 50) }

            val vecResults = vecJob.await()
            val bm25Results = bm25Job.await()

            Log.d("RagPipeline", "vec=${vecResults.size} bm25=${bm25Results.size}")

            val docCache = mutableMapOf<Long, String?>()
            suspend fun relativePath(docId: Long): String? =
                docCache.getOrPut(docId) { documentDao.getById(docId)?.relativePath }

            rrf(bm25Results, vecResults.map { it.second }, topK).map { (score, chunk) ->
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

        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { (id, score) -> Pair(score, chunks[id]!!) }
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
