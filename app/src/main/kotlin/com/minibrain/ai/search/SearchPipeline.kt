package com.minibrain.ai.search

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.agent.BM25SearchHitEvent
import com.minibrain.ai.agent.AgentTraceEvent
import com.minibrain.ai.agent.CandidateMergeEvent
import com.minibrain.ai.agent.MetadataSearchHitEvent
import com.minibrain.ai.agent.QueryExpansionEvent
import com.minibrain.ai.agent.DateResolver
import com.minibrain.ai.agent.RerankEvent
import com.minibrain.ai.agent.VectorSearchHitEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SourceType
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.search.NGramTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class SearchPipelineResult(
    val citations: List<Citation>,
    val traceEvents: List<AgentTraceEvent>,
)

class SearchPipeline(
    private val queryExpander: QueryExpander,
    private val llmReranker: LlmReranker,
    private val ragPipeline: RagPipeline,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
) {
    companion object {
        private const val TAG = "SearchPipeline"
        private const val CANDIDATE_LIMIT = 50
        private const val RERANK_TOP_K = 10
        private const val BM25_PER_QUERY_LIMIT = 20
        private const val VECTOR_LIMIT = 20
        private const val SNIPPET_CHARS = 200
        private const val MIN_FILENAME_MATCH_CHARS = 3
    }

    suspend fun search(
        query: String,
        treeUri: String,
        onStatus: (String) -> Unit = {},
    ): SearchPipelineResult {
        val traceEvents = mutableListOf<AgentTraceEvent>()

        // 1. Query Expansion (LLM 呼び出し — 単一スレッドのため逐次)
        onStatus("クエリ展開中...")
        val expanded = queryExpander.expand(query)
        traceEvents += QueryExpansionEvent(expanded)
        Log.d(TAG, "expanded=${expanded.size} queries")

        // 2. Parallel Retrieval
        onStatus("並行検索中...")
        val (bm25Candidates, metaCandidates, vectorCandidates) = coroutineScope {
            // 展開クエリごとに BM25 を並行実行
            val bm25Job = async(Dispatchers.IO) {
                expanded.flatMap { q -> bm25Search(q, treeUri) }
            }
            // メタデータ検索 + 日付範囲検索（DateResolver 経由）を合算
            val metaJob = async(Dispatchers.IO) {
                metadataSearch(expanded, treeUri) + dateRangeSearch(query, treeUri)
            }
            // ベクトル検索（元クエリのみ — 意味的近傍を拾う）
            val vectorJob = async(Dispatchers.IO) {
                vectorSearch(query, treeUri)
            }
            Triple(bm25Job.await(), metaJob.await(), vectorJob.await())
        }

        traceEvents += BM25SearchHitEvent(query, bm25Candidates.size)
        traceEvents += MetadataSearchHitEvent(metaCandidates.size)
        traceEvents += VectorSearchHitEvent(query, vectorCandidates.size)
        Log.d(TAG, "bm25=${bm25Candidates.size} meta=${metaCandidates.size} vector=${vectorCandidates.size}")

        // 3. Candidate Merge
        val allCandidates = bm25Candidates + metaCandidates + vectorCandidates
        val merged = mergeCandidates(allCandidates, CANDIDATE_LIMIT)
        traceEvents += CandidateMergeEvent(merged.size)
        Log.d(TAG, "merged=${merged.size} candidates")

        // 4. LLM Rerank (LLM 呼び出し — 逐次)
        onStatus("候補を絞り込み中...")
        val reranked = llmReranker.rerank(query, merged, RERANK_TOP_K)
        traceEvents += RerankEvent(before = merged.size, after = reranked.size)
        Log.d(TAG, "reranked=${reranked.size}")

        return SearchPipelineResult(reranked, traceEvents)
    }

    private suspend fun bm25Search(query: String, treeUri: String): List<Citation> {
        val matchQuery = NGramTokenizer.toFtsMatchQuery(query) ?: return emptyList()
        val sql = """
            SELECT chunks.* FROM chunks_fts
            JOIN chunks ON chunks_fts.rowid = chunks.id
            JOIN documents ON chunks.docId = documents.id
            WHERE chunks_fts MATCH ? AND documents.treeUri = ?
            LIMIT $BM25_PER_QUERY_LIMIT
        """.trimIndent()
        val chunks = runCatching {
            chunkDao.bm25SearchRaw(SimpleSQLiteQuery(sql, arrayOf<Any?>(matchQuery, treeUri)))
        }.getOrElse { e ->
            Log.w(TAG, "BM25 search failed for '$query': ${e.message}")
            emptyList()
        }
        return chunks.map { chunk ->
            Citation(
                headingPath = chunk.headingPath,
                snippet = chunk.text.take(SNIPPET_CHARS),
                score = 0.5f,
                docId = chunk.docId,
                source = SourceType.RRF,
            )
        }
    }

    private suspend fun metadataSearch(queries: List<String>, treeUri: String): List<Citation> {
        val allDocs = withContext(Dispatchers.IO) { documentDao.getAllByTree(treeUri) }
        val tokens = queries.flatMap { q ->
            q.split(Regex("[\\s　、。・]+")).filter { it.length >= 2 }
        }.distinct()

        return allDocs.filter { doc ->
            val fields = listOfNotNull(
                doc.fileName,
                doc.relativePath,
                doc.tags,
                doc.documentDate,
            )
            val tokenMatch = tokens.any { token ->
                fields.any { field -> field.contains(token, ignoreCase = true) }
            }
            // ファイル名逆引き: fileName(拡張子除く) が query の substring として含まれるか
            // 日本語助詞でトークン化されないクエリ（例: 「サウナしきじにいつ行ったっけ？」）でも
            // 固有名詞ファイル（「サウナしきじ.md」）を確実に拾うための保険
            val fileStem = doc.fileName.removeSuffix(".md").removeSuffix(".MD")
            val fileNameInQuery = fileStem.length >= MIN_FILENAME_MATCH_CHARS &&
                queries.any { q -> q.contains(fileStem, ignoreCase = true) }
            tokenMatch || fileNameInQuery
        }.map { doc ->
            Citation(
                headingPath = doc.relativePath,
                snippet = buildSnippetWithDate(doc.documentDate, doc.firstParagraph),
                score = 0.6f,
                docId = doc.id,
                relativePath = doc.relativePath,
                source = SourceType.METADATA,
            )
        }
    }

    private fun buildSnippetWithDate(documentDate: String?, firstParagraph: String?): String = buildString {
        if (!documentDate.isNullOrBlank()) append("[日付: $documentDate] ")
        append(firstParagraph ?: "")
    }

    private suspend fun vectorSearch(query: String, treeUri: String): List<Citation> =
        runCatching {
            ragPipeline.vectorOnlyTopK(query, treeUri, k = VECTOR_LIMIT)
        }.getOrElse { e ->
            Log.w(TAG, "Vector search failed: ${e.message}")
            emptyList()
        }

    // DateResolver を使って日付/期間クエリを DB の documentDate 範囲検索に変換する。
    // metadataSearch のトークン一致では "2021年3月" → "2021-03-15" が突合できないため専用で持つ。
    private suspend fun dateRangeSearch(query: String, treeUri: String): List<Citation> {
        // 期間クエリ（去年の夏・5年前の3月 など）→ getByDateRange で一括取得
        val dateRange = DateResolver.resolveDateRange(query)
        if (dateRange != null) {
            val docs = withContext(Dispatchers.IO) {
                documentDao.getByDateRange(treeUri, dateRange.start.toString(), dateRange.end.toString())
            }
            Log.d(TAG, "dateRangeSearch range=${dateRange.start}〜${dateRange.end} hits=${docs.size}")
            return docs.map { doc ->
                Citation(
                    headingPath = doc.relativePath,
                    snippet = buildSnippetWithDate(doc.documentDate, doc.firstParagraph),
                    score = 0.8f,
                    docId = doc.id,
                    relativePath = doc.relativePath,
                    source = SourceType.METADATA,
                )
            }
        }

        // 特定日付クエリ（2021年3月15日 など range にならないケース）→ 8桁数字で前方一致
        if (DateResolver.isDiaryQuery(query)) {
            val dateStrings = DateResolver.resolveToDateStrings(query)
            if (dateStrings.isNotEmpty()) {
                val allDocs = withContext(Dispatchers.IO) { documentDao.getAllByTree(treeUri) }
                val matched = allDocs.filter { doc ->
                    val docDate = doc.documentDate ?: return@filter false
                    val docDigits = docDate.replace("-", "")
                    dateStrings.any { date ->
                        val digits = date.replace("-", "")
                        docDigits.startsWith(digits) || digits.startsWith(docDigits)
                    }
                }
                Log.d(TAG, "dateRangeSearch specific dates=${dateStrings} hits=${matched.size}")
                return matched.map { doc ->
                    Citation(
                        headingPath = doc.relativePath,
                        snippet = doc.firstParagraph ?: "",
                        score = 0.8f,
                        docId = doc.id,
                        relativePath = doc.relativePath,
                        source = SourceType.METADATA,
                    )
                }
            }
        }

        return emptyList()
    }

    private fun mergeCandidates(candidates: List<Citation>, limit: Int): List<Citation> {
        // docId + headingPath で重複排除。同キーなら score 高い方を残す
        val seen = mutableMapOf<String, Citation>()
        candidates.forEach { c ->
            val key = "${c.docId}::${c.headingPath}"
            val existing = seen[key]
            if (existing == null || c.score > existing.score) {
                seen[key] = c
            }
        }
        return seen.values
            .sortedByDescending { it.score }
            .take(limit)
    }
}
