package com.minibrain.ai.search

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.agent.BM25SearchHitEvent
import com.minibrain.ai.agent.AgentTraceEvent
import com.minibrain.ai.agent.CandidateMergeEvent
import com.minibrain.ai.agent.MetadataSearchHitEvent
import com.minibrain.ai.agent.QueryExpansionEvent
import com.minibrain.ai.agent.DateRange
import com.minibrain.ai.agent.DateResolver
import com.minibrain.ai.agent.HyDeGeneratedEvent
import com.minibrain.ai.agent.RerankEvent
import com.minibrain.ai.agent.VectorSearchHitEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.RrfFuser
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.ai.rag.SourceType
import com.minibrain.ai.rag.dedupeKey
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import com.minibrain.data.search.NGramTokenizer
import com.minibrain.util.DatePrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    private val hyde: HyDE? = null,
) {
    companion object {
        private const val TAG = "SearchPipeline"
        private const val CANDIDATE_LIMIT = 50
        private const val RERANK_TOP_K = 10
        private const val BM25_PER_QUERY_LIMIT = 20
        private const val VECTOR_LIMIT = 20
        // 展開クエリ × Vector のサブクエリあたり件数（重い embed を抑えるため少し小さく）
        private const val VECTOR_LIMIT_PER_EXPANDED = 10
        // ベクトル候補の最低類似度スコア。E5（L2 正規化済）のコサインで概ね 0.40〜0.50 が境界。
        // 低スコアは Reranker のノイズ源になるため、ここで除外する（ADR-023）。
        private const val VECTOR_MIN_SCORE = 0.45f
        private const val SNIPPET_CHARS = 200
        private const val MIN_FILENAME_MATCH_CHARS = 3
        private const val RRF_K = 60
        // RRF 重み（[meta, vector, bm25]）。Metadata 完全一致 > BM25 > Vector の順。
        private val RRF_WEIGHTS = listOf(1.5f, 1.0f, 1.2f)
        // dateRange 検出時に Reranker 結果先頭へ強制注入する dateRangeSearch ヒット数（ADR-025）。
        private const val DATE_RANGE_PIN_COUNT = 5
        // dateRange ヒットのスニペット長。firstParagraph (200) では LLM が活動内容を読めず
        // 「内容が記載されていません」と返してしまうため、chunk テキストから長めに採る。
        private const val DATE_RANGE_SNIPPET_CHARS = 600
        // ファイル名一致 (topicMatch) ヒットのスニペット長（ADR-026）。
        // 「初回訪問日: YYYY/MM/DD」のようなラベル行が firstParagraph (200) からこぼれていても
        // 拾えるよう、先頭 chunk テキストから長めに採る。
        private const val TOPIC_MATCH_SNIPPET_CHARS = 500
    }

    suspend fun search(
        query: String,
        treeUri: String,
        onStatus: ((String) -> Unit)? = null,
        dateRange: DateRange? = DateResolver.resolveDateRange(query),
        cache: SearchRequestCache? = null,
    ): SearchPipelineResult {
        // cache が渡されなかった場合（EvalRunner や単独呼び出し）はリクエスト内でだけ使う
        // ローカルキャッシュを作る。AgentPipeline 経由なら ReAct 後段でも共有される。
        val ctx = cache ?: SearchRequestCache(treeUri, chunkDao, documentDao)
        val traceEvents = mutableListOf<AgentTraceEvent>()

        // 1. Query Expansion (LLM 呼び出し — 単一スレッドのため逐次)
        onStatus?.invoke("クエリ展開中...")
        val expanded = queryExpander.expand(query)
        traceEvents += QueryExpansionEvent(expanded)
        Log.d(TAG, "expanded=${expanded.size} queries")

        // 1.5 HyDE: 仮想回答を生成し、その埋め込みでベクトル検索を補強する。
        // LiteRT-LM は単一スレッドのため Query Expansion の直後に逐次実行する。
        // HyDE が無効 / 失敗した場合は null （元クエリのみ）にフォールバック。
        val hypothetical = hyde?.generateHypothetical(query)
        if (hypothetical != null) {
            traceEvents += HyDeGeneratedEvent(hypothetical.take(120))
            Log.d(TAG, "hyde=${hypothetical.take(80)}")
        }

        // 2. Parallel Retrieval
        onStatus?.invoke("並行検索中...")
        // dateRangeSearch は Reranker 後段の pin 注入でも再利用するため、別に保持する
        var dateRangeHits: List<Citation> = emptyList()
        val (bm25Candidates, metaCandidates, vectorCandidates) = coroutineScope {
            // 展開クエリごとに BM25 を並行実行
            val bm25Job = async(Dispatchers.IO) {
                expanded.flatMap { q -> bm25Search(q, treeUri) }
            }
            // 日付範囲検索（DateResolver 経由）+ メタデータ検索を合算
            // 日付ヒットを rank 先頭に置き、RRF 融合で優先されるようにする
            val metaJob = async(Dispatchers.IO) {
                val dateHits = dateRangeSearch(query, treeUri, dateRange, ctx)
                dateRangeHits = dateHits
                dateHits + metadataSearch(expanded, ctx)
            }
            // ベクトル検索: 展開クエリ全件 + （任意）HyDE 仮想回答を投入し、Recall を底上げする。
            // EmbedderService は Mutex で直列化されるため、ここを async にしても並列実行はされないが、
            // 他ジョブ（BM25 / metadata）とは並列に走る。低スコアの候補は閾値カットで除外する。
            val vectorJob = async(Dispatchers.IO) {
                multiVectorSearch(query, expanded, hypothetical, treeUri, ctx)
            }
            Triple(bm25Job.await(), metaJob.await(), vectorJob.await())
        }

        traceEvents += BM25SearchHitEvent(query, bm25Candidates.size)
        traceEvents += MetadataSearchHitEvent(metaCandidates.size)
        traceEvents += VectorSearchHitEvent(query, vectorCandidates.size)
        Log.d(TAG, "bm25=${bm25Candidates.size} meta=${metaCandidates.size} vector=${vectorCandidates.size}")

        // 3. Candidate Merge (RRF rank 融合 + ソース別重み付け)
        // meta を先頭に置き、同キー衝突時に [日付:] snippet 付き Citation を残す
        val merged = mergeCandidatesRrf(
            listOf(metaCandidates, vectorCandidates, bm25Candidates),
            CANDIDATE_LIMIT,
            RRF_K,
            weights = RRF_WEIGHTS,
        )
        traceEvents += CandidateMergeEvent(merged.size)
        Log.d(TAG, "merged=${merged.size} candidates")

        // 4. LLM Rerank (LLM 呼び出し — 逐次)
        onStatus?.invoke("候補を絞り込み中...")
        val reranked = llmReranker.rerank(query, merged, RERANK_TOP_K)
        traceEvents += RerankEvent(before = merged.size, after = reranked.size)
        Log.d(TAG, "reranked=${reranked.size}")

        // 4.5 dateRange 検出時は dateRangeSearch 上位 N 件を Reranker 結果の先頭に強制マージする（ADR-025）。
        // Reranker が日付ヒットを下位に圧縮するケースを救う最小介入。後段は Reranker 順を維持する。
        val final = if (dateRange != null && dateRangeHits.isNotEmpty()) {
            val pinned = dateRangeHits.take(DATE_RANGE_PIN_COUNT)
            val pinnedKeys = pinned.map { it.dedupeKey }.toHashSet()
            val rest = reranked.filterNot { it.dedupeKey in pinnedKeys }
            (pinned + rest).take(RERANK_TOP_K).also {
                Log.d(TAG, "dateRange pin: pinned=${pinned.size} final=${it.size}")
            }
        } else reranked

        return SearchPipelineResult(final, traceEvents)
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
                docId = chunk.docId,
                source = SourceType.BM25,
            )
        }
    }

    private suspend fun metadataSearch(queries: List<String>, ctx: SearchRequestCache): List<Citation> {
        val allDocs = ctx.documents()
        val tokens = queries.flatMap { q ->
            q.split(Regex("[\\s　、。・]+")).filter { it.length >= 2 }
        }.distinct()

        // topicMatch ヒットは先頭 chunk テキストでスニペットを組むため、必要なら 1 回だけロードする。
        // 普通のトークン一致のみで終わる場合は chunkVectors() を触らずに済ませる。
        var chunksByDocLazy: Map<Long, List<ChunkEntity>>? = null
        suspend fun firstChunkOf(docId: Long): ChunkEntity? {
            val map = chunksByDocLazy ?: ctx.chunkVectors().first.groupBy { it.docId }.also { chunksByDocLazy = it }
            return map[docId]?.firstOrNull()
        }

        return allDocs.mapNotNull { doc ->
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
            if (!tokenMatch && !fileNameInQuery) return@mapNotNull null

            // topicMatch ヒットは「初回訪問日: …」「YYYY/MM/DD」など本文の日付を後段の回答 LLM が
            // 拾えるよう、先頭 chunk テキストから長めの snippet を採る（ADR-026）。
            val snippetBody = if (fileNameInQuery) {
                firstChunkOf(doc.id)?.text?.take(TOPIC_MATCH_SNIPPET_CHARS) ?: doc.firstParagraph
            } else {
                doc.firstParagraph
            }
            Citation(
                headingPath = doc.relativePath,
                snippet = DatePrefix.build(doc.documentDate, snippetBody),
                docId = doc.id,
                relativePath = doc.relativePath,
                source = SourceType.METADATA,
                topicMatch = fileNameInQuery,
            )
        }
    }

    private suspend fun vectorSearch(
        query: String,
        treeUri: String,
        k: Int = VECTOR_LIMIT,
        ctx: SearchRequestCache,
    ): List<Citation> =
        runCatching {
            ragPipeline.vectorOnlyTopK(query, treeUri, k = k, cache = ctx)
                .filter { it.score >= VECTOR_MIN_SCORE }
        }.getOrElse { e ->
            Log.w(TAG, "Vector search failed: ${e.message}")
            emptyList()
        }

    // 元クエリ + 展開クエリ + HyDE 仮想回答でベクトル検索を実行する。
    // R4: 同一テキストへの embed を防ぐため、まず正規化 + distinct でユニークなクエリ集合を作る。
    // 主クエリ（先頭）を VECTOR_LIMIT、それ以降を VECTOR_LIMIT_PER_EXPANDED の枠で検索し、
    // (docId, headingPath) で重複排除する。
    private suspend fun multiVectorSearch(
        originalQuery: String,
        expanded: List<String>,
        hypothetical: String?,
        treeUri: String,
        ctx: SearchRequestCache,
    ): List<Citation> {
        val ordered = LinkedHashSet<String>()
        fun addIfValid(s: String?) {
            if (s.isNullOrBlank()) return
            val normalized = s.trim().replace(Regex("\\s+"), " ")
            if (normalized.isNotEmpty()) ordered.add(normalized)
        }
        addIfValid(originalQuery)
        expanded.forEach(::addIfValid)
        addIfValid(hypothetical)

        val seen = HashSet<String>()
        val out = mutableListOf<Citation>()
        ordered.forEachIndexed { idx, q ->
            val k = if (idx == 0) VECTOR_LIMIT else VECTOR_LIMIT_PER_EXPANDED
            vectorSearch(q, treeUri, k = k, ctx = ctx).forEach { c ->
                if (seen.add(c.dedupeKey)) out += c
            }
        }
        return out
    }

    // 日付/期間クエリを DB の documentDate 範囲検索に変換する。
    // metadataSearch のトークン一致では "2021年3月" → "2021-03-15" が突合できないため専用で持つ。
    // dateRange は AgentPipeline で一度だけ解決された値を受け取る（二重解決を避ける）
    private suspend fun dateRangeSearch(
        query: String,
        treeUri: String,
        dateRange: DateRange?,
        ctx: SearchRequestCache,
    ): List<Citation> {
        // 期間クエリ（去年の夏・5年前の3月 など）→ getByDateRange で一括取得
        // documents() がキャッシュ済みなら DB を叩かずに同等のフィルタを実行する
        if (dateRange != null) {
            val docs = filterDocsByDateRange(ctx, dateRange)
            Log.d(TAG, "dateRangeSearch range=${dateRange.start}〜${dateRange.end} hits=${docs.size}")
            if (docs.isEmpty()) return emptyList()

            // 各 doc の先頭 chunk テキストを使って「活動内容」を pin に乗せる。
            // firstParagraph (200 文字) では LLM が「内容が記載されていません」と返す問題への対処（ADR-025）
            val (chunks, _) = ctx.chunkVectors()
            val chunksByDoc = chunks.groupBy { it.docId }
            return docs.map { doc ->
                val firstChunk = chunksByDoc[doc.id]?.firstOrNull()
                val body = firstChunk?.text?.take(DATE_RANGE_SNIPPET_CHARS)
                    ?: doc.firstParagraph
                Citation(
                    headingPath = firstChunk?.headingPath ?: doc.relativePath,
                    snippet = DatePrefix.build(doc.documentDate, body),
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
                val allDocs = ctx.documents()
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
                        // CoverageChecker の [日付:] 短絡が効くよう日付プレフィックスを付与
                        snippet = DatePrefix.build(doc.documentDate, doc.firstParagraph),
                        docId = doc.id,
                        relativePath = doc.relativePath,
                        source = SourceType.METADATA,
                    )
                }
            }
        }

        return emptyList()
    }

    // documentDate は ISO 文字列(YYYY-MM-DD)で保存されており辞書順 = 時系列順なので
    // 文字列比較で安全に範囲フィルタできる。元の SQL `WHERE documentDate >= ? AND ... <= ?` 相当。
    private suspend fun filterDocsByDateRange(
        ctx: SearchRequestCache,
        range: DateRange,
    ): List<DocumentEntity> {
        val start = range.start.toString()
        val end = range.end.toString()
        return ctx.documents()
            .filter { doc ->
                val d = doc.documentDate ?: return@filter false
                d >= start && d <= end
            }
            .sortedBy { it.documentDate }
    }
}

// ソース別 rank リストを RRF（Reciprocal Rank Fusion）で融合する。
// score = Σ weight × 1/(k + rank + 1)。複数ソースに出現する候補ほど加点され、
// ソースごとの擬似スコアの大小に依存しない（ADR-022）。
// weights を渡すと「信頼度の高いソースを上位に寄せる」非対称重み付けが可能（ADR-023）。
// docId + headingPath で重複排除し、同キーは最初に出現した Citation を保持する。
internal fun mergeCandidatesRrf(
    rankLists: List<List<Citation>>,
    limit: Int,
    k: Int = 60,
    weights: List<Float>? = null,
): List<Citation> = RrfFuser.fuse(
    rankLists = rankLists,
    keyOf = { it.dedupeKey },
    k = k,
    weights = weights,
).sortedByDescending { it.score }
    .take(limit)
    .map { it.item.copy(score = it.score) }
