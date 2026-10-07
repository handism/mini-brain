package com.minibrain.ai.search

import com.minibrain.ai.agent.DateRange
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.ai.rag.SourceType
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class SearchPipelineTest {

    private lateinit var queryExpander: QueryExpander
    private lateinit var llmReranker: LlmReranker
    private lateinit var ragPipeline: RagPipeline
    private lateinit var chunkDao: ChunkDao
    private lateinit var documentDao: DocumentDao
    private lateinit var hyde: HyDE
    private lateinit var searchPipeline: SearchPipeline
    private lateinit var cache: SearchRequestCache

    @Before
    fun setup() {
        // Plant Timber fake
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}
        })

        queryExpander = mockk()
        llmReranker = mockk()
        ragPipeline = mockk()
        chunkDao = mockk()
        documentDao = mockk()
        hyde = mockk()
        cache = mockk()
        // 期間フィルタは実装を使い、各テストでスタブした documents() に対して絞り込ませる
        coEvery { cache.documentsInDateRange(any(), any()) } answers { callOriginal() }
        coEvery { ragPipeline.prefetchQueryEmbeddings(any(), any()) } returns Unit

        searchPipeline = SearchPipeline(
            queryExpander = queryExpander,
            llmReranker = llmReranker,
            ragPipeline = ragPipeline,
            chunkDao = chunkDao,
            documentDao = documentDao,
            hyde = hyde
        )
    }

    private fun citation(docId: Long, heading: String, source: SourceType = SourceType.UNKNOWN, score: Float = 0f, topicMatch: Boolean = false) =
        Citation(headingPath = heading, snippet = "test", docId = docId, source = source, score = score, topicMatch = topicMatch)

    @Test
    fun `search executes successfully and merges candidates from different sources`() = runTest {
        val query = "test query"
        val treeUri = "tree/uri"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns emptyList()
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { cache.treeUri } returns treeUri

        val bm25Citation = citation(1, "A", SourceType.BM25)
        val vectorCitation = citation(2, "B", SourceType.VECTOR, score = 0.5f)
        val metaCitation = citation(3, "C", SourceType.METADATA)

        // Mock BM25 search
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns listOf(
            ChunkEntity(id = 1, docId = 1, text = "bm25 text", embedding = ByteArray(0), headingPath = "A")
        )

        // Mock Vector search
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns listOf(vectorCitation)

        // Mock Reranker
        coEvery { llmReranker.rerank(query, any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val candidates = it.invocation.args[1] as List<Citation>
            candidates.take(10)
        }

        val result = searchPipeline.search(query, treeUri, cache = cache)

        // The reranker is mocked to just return the candidates it receives.
        // We expect at least the vector and bm25 candidates.
        val docIds = result.citations.map { it.docId }
        assertTrue(docIds.contains(1L))
        assertTrue(docIds.contains(2L))
    }

    @Test
    fun `search uses HyDE generated query if available`() = runTest {
        val query = "test query"
        val treeUri = "tree/uri"
        val hypothetical = "hypothetical answer"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns hypothetical
        coEvery { cache.documents() } returns emptyList()
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { llmReranker.rerank(query, any(), any()) } returns emptyList()

        searchPipeline.search(query, treeUri, cache = cache)

        // Verify vector search was called with the hypothetical query
        coVerify { ragPipeline.vectorOnlyTopK(hypothetical, treeUri, any(), cache) }
    }

    @Test
    fun `search pins date range hits to the top`() = runTest {
        val query = "test query"
        val treeUri = "tree/uri"
        val dateRange = DateRange(LocalDate.parse("2023-01-01"), LocalDate.parse("2023-12-31"))

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns listOf(
            DocumentEntity(id = 5, treeUri = treeUri, fileUri = "uri", fileName = "test.md", relativePath = "test.md", lastModified = 0L, contentHash = "", firstParagraph = "test", documentDate = "2023-06-01")
        )
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()

        // Mock Reranker to put some other citation first, or reverse the list
        val citation5 = citation(5, "test.md", SourceType.METADATA)
        val citation6 = citation(6, "other", SourceType.VECTOR)

        coEvery { llmReranker.rerank(query, any(), any()) } returns listOf(citation6, citation5)

        val result = searchPipeline.search(query, treeUri, dateRange = dateRange, cache = cache)

        // Date range hit should be pinned to the top despite reranker order
        assertEquals(5L, result.citations[0].docId)
        assertEquals(6L, result.citations[1].docId)
    }

    @Test
    fun `date range pins prefer docs the reranker ranked high over earlier dates`() = runTest {
        val query = "先月の振り返りミーティング"
        val treeUri = "tree/uri"
        val dateRange = DateRange(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))

        // 9 月の日記 6 件（1〜6 日）と、月末の振り返り議事録（id=24）
        val dailies = (1L..6L).map { day ->
            DocumentEntity(id = day, treeUri = treeUri, fileUri = "d$day", fileName = "2026-09-0$day.md", relativePath = "daily/2026-09-0$day.md", lastModified = 0L, contentHash = "", firstParagraph = "日記", documentDate = "2026-09-0$day")
        }
        val meeting = DocumentEntity(id = 24, treeUri = treeUri, fileUri = "m", fileName = "2026-09-24 振り返り.md", relativePath = "work/meetings/2026-09-24 振り返り.md", lastModified = 0L, contentHash = "", firstParagraph = "振り返り", documentDate = "2026-09-24")

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns dailies + meeting
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { llmReranker.rerank(query, any(), any()) } returns listOf(citation(24, "振り返り", SourceType.VECTOR))

        val result = searchPipeline.search(query, treeUri, dateRange = dateRange, cache = cache)

        // 日付順の先頭 5 件（1〜5 日）ではなく、Reranker が選んだ議事録を先頭に固定する
        assertEquals(24L, result.citations[0].docId)
    }

    @Test
    fun `collapseByDoc keeps one citation per doc at its best position`() {
        val merged = listOf(
            citation(1, "a#2", SourceType.VECTOR),
            citation(2, "b#1", SourceType.BM25),
            citation(1, "a#1", SourceType.METADATA, topicMatch = true),
            Citation(headingPath = "フォルダ: x", snippet = "", source = SourceType.FOLDER),
            citation(3, "c#1", SourceType.VECTOR).copy(snippet = "関係する本文"),
            citation(3, "c#2", SourceType.METADATA).copy(snippet = "[日付: 2026-09-01] 冒頭"),
            citation(2, "b#2", SourceType.VECTOR),
        )

        val collapsed = SearchPipeline.collapseByDoc(merged)

        assertEquals(listOf(1L, 2L, null, 3L), collapsed.map { it.docId })
        // ファイル名一致は、順位が低くてもそのファイルの代表として残す
        assertEquals("a#1", collapsed[0].headingPath)
        assertTrue(collapsed[0].topicMatch)
        assertEquals("b#1", collapsed[1].headingPath)
        // 日付ヒットは本文の近いチャンクを代表にしたまま、日付だけを引き継ぐ
        assertEquals("c#1", collapsed[3].headingPath)
        assertEquals("[日付: 2026-09-01] 関係する本文", collapsed[3].snippet)
    }

    @Test
    fun `keepRrfTop puts back RRF top docs the reranker dropped`() {
        val merged = (0L until 12L).map { citation(it, "h$it", SourceType.VECTOR) }
        // Reranker は RRF の 2 位（docId=1）を落とし、下位から 10 件を選んだ
        val reranked = listOf(0L, 4, 6, 7, 9, 11, 2, 3, 8, 10).map { id -> merged[id.toInt()] }

        val result = SearchPipeline.keepRrfTop(reranked, merged, topK = 10)

        assertEquals(listOf(0L, 4, 6, 7, 9, 11, 2, 3, 8, 1), result.map { it.docId })
        // 上位 3 件が全部残っていれば何もしない
        assertEquals(merged.take(10), SearchPipeline.keepRrfTop(merged.take(10), merged, topK = 10))
    }

    @Test
    fun `enumeration scope widens to the top folder only when it is small`() {
        val paths = listOf(
            "travel/京都.md", "travel/北海道.md", "travel/キャンプ/長野.md", "travel-plan/欧州.md",
        ) + (1..9).map { "journal/2025-0$it.md" } + listOf("journal/old/2024.md")

        // travel/キャンプ が近くても、travel 全体（3 件）を対象にする。travel-plan は含めない
        assertEquals("travel", SearchPipeline.enumerationScope("travel/キャンプ", paths))
        // journal は 10 件で多すぎるので、近いフォルダ自身にする
        assertEquals("journal/old", SearchPipeline.enumerationScope("journal/old", paths))
        assertEquals(null, SearchPipeline.enumerationScope("journal", paths))
    }

    @Test
    fun `isEnumerationQuery detects listing questions`() {
        assertTrue(SearchPipeline.isEnumerationQuery("これまでに行った旅行を全部教えて"))
        assertTrue(SearchPipeline.isEnumerationQuery("作ったことのある料理のレシピ一覧"))
        assertEquals(false, SearchPipeline.isEnumerationQuery("スパイス堂ってどんな店？"))
    }

    @Test
    fun `enumeration query pins every doc of the nearest folder`() = runTest {
        val query = "これまでに行った旅行を全部教えて"
        val treeUri = "tree/uri"
        fun doc(id: Long, path: String) = DocumentEntity(id = id, treeUri = treeUri, fileUri = "u$id", fileName = path.substringAfterLast('/'), relativePath = path, lastModified = 0L, contentHash = "", firstParagraph = "本文$id")
        val docs = listOf(
            doc(1, "travel/京都.md"), doc(2, "travel/沖縄.md"), doc(3, "travel/キャンプ/長野.md"),
            doc(4, "journal/2025-07.md"), doc(5, "travel-plan/欧州.md"),
        )

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns docs
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { ragPipeline.nearestFolder(query, treeUri, cache) } returns "travel/キャンプ"
        // Reranker は日記と京都だけを残した
        coEvery { llmReranker.rerank(query, any(), any()) } returns listOf(
            citation(4, "journal", SourceType.VECTOR).copy(relativePath = "journal/2025-07.md"),
            citation(1, "京都", SourceType.VECTOR).copy(relativePath = "travel/京都.md"),
        )

        val result = searchPipeline.search(query, treeUri, cache = cache)
        val paths = result.citations.map { it.relativePath }

        // travel 配下の 3 件を、Reranker が上げた京都 → パス順で先頭に置き、日記はその後ろに残す
        assertEquals(listOf("travel/京都.md", "travel/キャンプ/長野.md", "travel/沖縄.md", "journal/2025-07.md"), paths)
    }

    @Test
    fun `search matches single character filename for metadata topicMatch`() = runTest {
        val query = "歯についての記録"
        val treeUri = "tree/uri"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns listOf(
            DocumentEntity(id = 7, treeUri = treeUri, fileUri = "uri", fileName = "歯.md", relativePath = "歯.md", lastModified = 0L, contentHash = "", firstParagraph = "歯の調子について", documentDate = null)
        )
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { llmReranker.rerank(query, any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[1] as List<Citation>
        }

        val result = searchPipeline.search(query, treeUri, cache = cache)

        val topicMatchCitation = result.citations.find { it.docId == 7L }
        assertTrue(topicMatchCitation != null)
        assertTrue(topicMatchCitation!!.topicMatch)
    }

    @Test
    fun `search deduplicates and normalizes expanded queries correctly`() = runTest {
        val originalQuery = "   original   query   " // will be normalized to "original query"
        val expanded = listOf("expanded 1", " expanded  1 ", "", "  ") // " expanded  1 " becomes "expanded 1", "" and "  " ignored
        val hypothetical = "original   query" // duplicate of normalized original

        coEvery { queryExpander.expand(originalQuery) } returns expanded
        coEvery { hyde.generateHypothetical(originalQuery) } returns hypothetical

        coEvery { cache.documents() } returns emptyList()
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), any(), any()) } returns emptyList()

        coEvery { llmReranker.rerank(any(), any(), any()) } returns emptyList()

        val capturedQueries = mutableListOf<String>()
        coEvery { ragPipeline.vectorOnlyTopK(capture(capturedQueries), any(), any(), any()) } returns emptyList()

        searchPipeline.search(originalQuery, "tree/uri", cache = cache)

        assertEquals(2, capturedQueries.size)
        assertEquals("original query", capturedQueries[0])
        assertEquals("expanded 1", capturedQueries[1])
    }

    @Test
    fun `metadataSearch correctly uses firstChunkOf lazy loading`() = runTest {
        val query = "歯周病と歯についての記録"
        val treeUri = "tree/uri"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null

        // Topic match needs documents where the filename is contained in the query
        val doc1 = DocumentEntity(id = 7, treeUri = treeUri, fileUri = "uri1", fileName = "歯.md", relativePath = "歯.md", lastModified = 0L, contentHash = "", firstParagraph = "first paragraph 1", documentDate = null)
        val doc2 = DocumentEntity(id = 8, treeUri = treeUri, fileUri = "uri2", fileName = "歯周病.md", relativePath = "歯周病.md", lastModified = 0L, contentHash = "", firstParagraph = "first paragraph 2", documentDate = null)
        coEvery { cache.documents() } returns listOf(doc1, doc2)

        val chunk1 = ChunkEntity(id = 1, docId = 7, text = "long chunk text that exceeds first paragraph and contains topic match snippet for doc 1", embedding = ByteArray(0), headingPath = "A")
        val chunk2 = ChunkEntity(id = 2, docId = 8, text = "long chunk text for doc 2", embedding = ByteArray(0), headingPath = "B")

        coEvery { cache.firstChunkOf(7L) } returns chunk1
        coEvery { cache.firstChunkOf(8L) } returns chunk2

        // Mock other pipeline steps to return empty or pass-through
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { llmReranker.rerank(query, any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[1] as List<Citation>
        }

        val result = searchPipeline.search(query, treeUri, cache = cache)

        // Verify that topic match citations have the expanded snippet from the chunks
        val citation1 = result.citations.find { it.docId == 7L }
        assertTrue(citation1 != null)
        assertEquals("long chunk text that exceeds first paragraph and contains topic match snippet for doc 1", citation1?.snippet)

        val citation2 = result.citations.find { it.docId == 8L }
        assertTrue(citation2 != null)
        assertEquals("long chunk text for doc 2", citation2?.snippet)

        // topicMatch に当たった doc の分だけ chunk を引く。chunks 全体のロード/メモ化は
        // SearchRequestCache 側の責務で、SearchPipeline は chunkVectors() を直接触らない。
        coVerify(exactly = 1) { cache.firstChunkOf(7L) }
        coVerify(exactly = 1) { cache.firstChunkOf(8L) }
        coVerify(exactly = 0) { cache.chunkVectors() }
    }

    @Test
    fun `search skips LLM reranker for date query with topic match`() = runTest {
        val query = "スパイス堂にいつ行ったっけ"
        val treeUri = "tree/uri"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns listOf(
            DocumentEntity(id = 8, treeUri = treeUri, fileUri = "uri", fileName = "スパイス堂.md", relativePath = "food/スパイス堂.md", lastModified = 0L, contentHash = "", firstParagraph = "初回訪問日: 2024/11/03", documentDate = null)
        )
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns
            (1L..12L).map { citation(100 + it, "v$it", SourceType.VECTOR, score = 0.9f) }

        val result = searchPipeline.search(query, treeUri, cache = cache)

        coVerify(exactly = 0) { llmReranker.rerank(any(), any(), any()) }
        assertEquals(8L, result.citations[0].docId)
        assertTrue(result.citations[0].topicMatch)
        assertEquals(10, result.citations.size)
    }

    @Test
    fun `search prefetches all vector query embeddings in one call`() = runTest {
        val query = "original"
        coEvery { queryExpander.expand(query) } returns listOf("a", "b")
        coEvery { hyde.generateHypothetical(query) } returns "hyde"
        coEvery { cache.documents() } returns emptyList()
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { chunkDao.bm25SearchByTree(any(), any(), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), any(), any(), any()) } returns emptyList()
        coEvery { llmReranker.rerank(any(), any(), any()) } returns emptyList()

        searchPipeline.search(query, "tree/uri", cache = cache)

        coVerify(exactly = 1) { ragPipeline.prefetchQueryEmbeddings(listOf("original", "a", "b", "hyde"), cache) }
    }

    @Test
    fun `BM25 候補にも relativePath が付き、Reranker 前の候補が結果に残る`() = runTest {
        val query = "ビーフカレー"
        val treeUri = "tree/uri"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns listOf(
            DocumentEntity(id = 9, treeUri = treeUri, fileUri = "uri9", fileName = "note.md", relativePath = "food/note.md", lastModified = 0L, contentHash = "", firstParagraph = "", documentDate = null)
        )
        coEvery { cache.firstChunkOf(any()) } returns null
        coEvery { cache.treeUri } returns treeUri
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns listOf(
            ChunkEntity(id = 1, docId = 9, text = "ビーフカレーが最高", embedding = ByteArray(0), headingPath = "food/note.md > 感想")
        )
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { llmReranker.rerank(query, any(), any()) } returns emptyList()

        val result = searchPipeline.search(query, treeUri, cache = cache)

        val bm25 = result.candidates.single { it.source == SourceType.BM25 }
        assertEquals("food/note.md", bm25.relativePath)
    }

    @Test
    fun `質問に書かれたフォルダを見つける`() {
        val paths = listOf(
            "travel/京都.md", "travel/キャンプ/長野 白樺湖.md", "travel-plan/屋久島.md",
            "work/meetings/a.md", "work/オンボーディング.md", "food/レシピ/カレー.md",
        )
        assertEquals("travel-plan", SearchPipeline.explicitFolder("travel-plan にある行きたい場所", paths))
        assertEquals("work/meetings", SearchPipeline.explicitFolder("work/meetings の議事録を一覧で", paths))
        assertEquals("travel/キャンプ", SearchPipeline.explicitFolder("キャンプフォルダの中身", paths))
        // 名前の一部（travel-plan の travel）や、後ろにフォルダの合図が無い 1 段の名前は数えない
        assertEquals(null, SearchPipeline.explicitFolder("travelplan について", paths))
        assertEquals(null, SearchPipeline.explicitFolder("作ったことのある料理のレシピ一覧", paths))
        assertEquals(null, SearchPipeline.explicitFolder("how does work go", paths))
    }
}
