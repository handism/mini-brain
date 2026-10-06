package com.minibrain.ai.rag

import com.minibrain.ai.embed.EmbedType
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.search.bm25SearchOrEmpty
import com.minibrain.data.db.entities.FolderEmbeddingEntity
import com.minibrain.util.runCatchingCancellable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SourceType { READ_FILE, GREP, METADATA, BM25, VECTOR, RRF, GLOB, FOLDER, UNKNOWN }

data class Citation(
    val headingPath: String,
    val snippet: String,
    val score: Float = 0f,
    val docId: Long? = null,
    val relativePath: String? = null,
    val source: SourceType = SourceType.UNKNOWN,
    // ファイル名が質問の substring として一致したことを示すフラグ。
    // 「スパイス堂にいつ行ったっけ」のような固有名詞ヒットを、後段（Reranker / CoverageChecker /
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
        treeUri: String,
        topK: Int = 20,
        cache: SearchRequestCache? = null,
    ): List<Citation> =
        coroutineScope {
            val ctx = cacheFor(treeUri, cache)
            // vector / folder の両検索で同じクエリベクトルを使うため、embed は 1 回だけ行う
            val queryVecJob = async(start = CoroutineStart.LAZY) {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { embedQuery(question, ctx) }
                    ?: run { Timber.tag(TAG).w("query embed timed out"); null }
            }
            val vecJob = async {
                val queryVec = queryVecJob.await() ?: return@async emptyList()
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { cosineTopK(ctx, queryVec, 50) }
                    ?: run { Timber.tag(TAG).w("vectorSearch timed out"); emptyList() }
            }
            val bm25Job = async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { chunkDao.bm25SearchOrEmpty(question, treeUri, 50) }
                    ?: run { Timber.tag(TAG).w("bm25Search timed out"); emptyList() }
            }
            val folderJob = async {
                val queryVec = queryVecJob.await() ?: return@async emptyList()
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { folderSearch(queryVec, treeUri, k = 5) }
                    ?: run { Timber.tag(TAG).w("folderSearch timed out"); emptyList() }
            }

            val vecResults = vecJob.await()
            val bm25Results = bm25Job.await()
            val folderResults = folderJob.await()

            Timber.tag(TAG).d("vec=${vecResults.size} bm25=${bm25Results.size} folder=${folderResults.size}")

            val docsById = ctx.documents().associateBy { it.id }
            val docIdToDate = { docId: Long ->
                docsById[docId]?.documentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            }

            val chunkCitations = rrf(
                RrfParams(
                    bm25Results = bm25Results,
                    vecResults = vecResults.map { it.second },
                    topK = topK,
                    docIdToDate = docIdToDate,
                )
            ).map { (score, chunk) ->
                    Timber.tag(TAG).d("rrf=%.4f path=${chunk.headingPath}".format(score))
                    Citation(
                        headingPath = chunk.headingPath,
                        snippet = chunk.text,
                        score = score,
                        docId = chunk.docId,
                        relativePath = docsById[chunk.docId]?.relativePath,
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
        val ctx = cacheFor(treeUri, cache)
        val hits = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { cosineTopK(ctx, embedQuery(question, ctx), k) }
            ?: run { Timber.tag(TAG).w("vectorOnlyTopK timed out"); return emptyList() }
        val docsById = ctx.documents().associateBy { it.id }
        return hits.map { (score, chunk) ->
            Citation(
                headingPath = chunk.headingPath,
                snippet = chunk.text,
                score = score,
                docId = chunk.docId,
                relativePath = docsById[chunk.docId]?.relativePath,
                source = SourceType.VECTOR,
            )
        }
    }

    /** クエリの埋め込みに最も近いフォルダの相対パス。フォルダ埋め込みが無ければ null（ADR-046） */
    suspend fun nearestFolder(question: String, treeUri: String, cache: SearchRequestCache? = null): String? {
        val ctx = cacheFor(treeUri, cache)
        return withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            folderSearch(embedQuery(question, ctx), treeUri, k = 1).firstOrNull()?.second?.path
        } ?: run { Timber.tag(TAG).w("nearestFolder timed out or empty"); null }
    }

    // AgentPipeline 経由なら共有キャッシュを使い、単独呼び出し（EvalRunner・テスト）では
    // この呼び出し内だけのキャッシュを作る。doc / chunk の参照はすべてキャッシュ経由に統一する
    private fun cacheFor(treeUri: String, cache: SearchRequestCache?): SearchRequestCache =
        cache?.takeIf { it.treeUri == treeUri } ?: SearchRequestCache(treeUri, chunkDao, documentDao)

    /**
     * 複数クエリの埋め込みを 1 回の推論で先に計算して cache に入れる。
     * 失敗しても vectorOnlyTopK 側が 1 件ずつ embed し直すので、ここではログだけ残す。
     */
    suspend fun prefetchQueryEmbeddings(queries: List<String>, cache: SearchRequestCache) {
        runCatchingCancellable {
            cache.prefetchQueryEmbeddings(queries) { embedderService.embedAll(it, EmbedType.QUERY) }
        }.onFailure { Timber.tag(TAG).w(it, "query embedding prefetch failed") }
    }

    // 同一リクエスト内の同じクエリは embed し直さない
    private suspend fun embedQuery(question: String, ctx: SearchRequestCache): FloatArray =
        ctx.queryEmbedding(question) { embedderService.embed(it, EmbedType.QUERY) }

    // 全件ドット積は CPU 負荷が高いので Default で回す
    private suspend fun cosineTopK(ctx: SearchRequestCache, queryVec: FloatArray, k: Int): List<Pair<Float, ChunkEntity>> =
        withContext(Dispatchers.Default) { ctx.cosineTopK(queryVec, k) }

    private suspend fun folderSearch(queryVec: FloatArray, treeUri: String, k: Int): List<Pair<Float, FolderEmbeddingEntity>> =
        withContext(Dispatchers.Default) {
            val folders = withContext(Dispatchers.IO) { folderEmbeddingDao.getAllByTree(treeUri) }
            if (folders.isEmpty()) return@withContext emptyList()
            val candidates = folders.map { fe ->
                Pair(EmbedderService.bytesToFloatArray(fe.embedding), fe)
            }
            CosineSimilarity.topK(queryVec, candidates, k)
        }

    private data class RrfParams(
        val bm25Results: List<ChunkEntity>,
        val vecResults: List<ChunkEntity>,
        val topK: Int,
        val k: Int = 60,
        val docIdToDate: (Long) -> LocalDate? = { null },
    )

    private fun rrf(params: RrfParams): List<Pair<Float, ChunkEntity>> {
        val fused = RrfFuser.fuse(
            rankLists = listOf(params.bm25Results, params.vecResults),
            keyOf = { it.id },
            k = params.k,
        )
        val today = LocalDate.now()
        return fused
            .map { entry ->
                val boost = freshnessBoost(params.docIdToDate(entry.item.docId), today)
                Pair(entry.score + boost, entry.item)
            }
            .sortedByDescending { it.first }
            .take(params.topK)
    }

    companion object {
        private const val TAG = "RagPipeline"
        private const val SEARCH_TIMEOUT_MS = 8_000L
        // freshnessBoost tuning constants — adjust to balance recency vs. relevance
        // RRF max score ≈ 0.032 (rank=1 in both BM25 and vector)
        internal const val FRESHNESS_BOOST_MAX  = 0.010f  // 最大加点 (RRF max の約 30%)
        // exp(-days/90) の時定数。半減期は 90×ln2 ≈ 62 日 (30d:~0.0072, 1y:~0.00017, 3y:≈0)
        internal const val FRESHNESS_DECAY_DAYS = 90f

        fun freshnessBoost(docDate: LocalDate?, today: LocalDate): Float {
            if (docDate == null) return 0f
            val days = ChronoUnit.DAYS.between(docDate, today).coerceAtLeast(0).toFloat()
            return (FRESHNESS_BOOST_MAX * exp(-days / FRESHNESS_DECAY_DAYS)).toFloat()
        }
    }
}
