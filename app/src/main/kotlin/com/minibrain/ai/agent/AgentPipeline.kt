package com.minibrain.ai.agent

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.CosineSimilarity
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import com.minibrain.data.search.NGramTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AgentPipeline(
    private val llmService: LlmService,
    private val embedderService: EmbedderService,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
    private val ragPipeline: RagPipeline,
) {
    companion object {
        private const val TAG = "AgentPipeline"
        private const val MAX_FILES_IN_PROMPT = 50
        private const val MAX_CHUNKS_PER_DOC = 15
        // maxNumTokens=4096 のうち出力用に ~1024 トークン確保。残りを日本語換算 ~2chars/token で割り当て
        private const val MAX_CITATION_CHARS = 4000
    }

    suspend fun planSearch(
        question: String,
        treeUri: String,
        onStatus: (String) -> Unit = {},
    ): SearchPlan = withContext(Dispatchers.Default) {
        // 1. 日付関連キーワードによるクライアントサイド判定
        if (DateResolver.isDiaryQuery(question)) {
            Log.d(TAG, "diary_lookup: $question")
            return@withContext SearchPlan(
                intent = "diary_lookup",
                reasoning = "日付関連キーワードを検出",
                targetFolders = emptyList(),
                targetFiles = emptyList(),
                searchKeywords = DateResolver.resolveToDateStrings(question),
            )
        }

        // 2. ファイル名一致によるクライアントサイド判定
        val allDocs = withContext(Dispatchers.IO) { documentDao.getAllByTree(treeUri) }
        val fileMatches = allDocs.filter { doc ->
            val name = doc.fileName.removeSuffix(".md").lowercase()
            name.length >= 3 && question.lowercase().contains(name)
        }
        if (fileMatches.isNotEmpty()) {
            Log.d(TAG, "file_lookup: ${fileMatches.map { it.fileName }}")
            return@withContext SearchPlan(
                intent = "file_lookup",
                reasoning = "ファイル名が質問に含まれている",
                targetFolders = emptyList(),
                targetFiles = fileMatches.map { it.relativePath },
                searchKeywords = emptyList(),
            )
        }

        // 3. LLM による検索計画生成
        if (!llmService.isReady() || allDocs.isEmpty()) {
            return@withContext SearchPlan(
                intent = "general",
                reasoning = "LLM未初期化またはインデックスなし",
                targetFolders = emptyList(),
                targetFiles = emptyList(),
                searchKeywords = emptyList(),
            )
        }

        onStatus("検索計画を作成中...")
        val prompt = buildPlanPrompt(question, buildFolderTree(allDocs), buildFileListForPrompt(allDocs))

        val sb = StringBuilder()
        runCatching {
            llmService.generateStream(prompt).collect { token -> sb.append(token) }
        }.onFailure { Log.w(TAG, "plan LLM failed: ${it.message}") }

        parsePlanJson(sb.toString()) ?: SearchPlan(
            intent = "general",
            reasoning = "JSON解析失敗",
            targetFolders = emptyList(),
            targetFiles = emptyList(),
            searchKeywords = emptyList(),
        )
    }

    suspend fun executeSearch(
        plan: SearchPlan,
        question: String,
        treeUri: String,
        onStatus: (String) -> Unit = {},
    ): List<Citation> = withContext(Dispatchers.IO) {
        Log.d(TAG, "execute intent=${plan.intent} folders=${plan.targetFolders} files=${plan.targetFiles} keywords=${plan.searchKeywords}")
        when (plan.intent) {
            "diary_lookup" -> executeDiaryLookup(plan, treeUri, onStatus)
            "topic_research" -> executeTopicResearch(plan, question, treeUri, onStatus)
            "file_lookup" -> executeFileLookup(plan, treeUri, onStatus)
            else -> {
                onStatus("関連情報を検索中...")
                ragPipeline.retrieveTopChunks(question)
            }
        }
    }

    fun answer(
        question: String,
        citations: List<Citation>,
        plan: SearchPlan,
        recentHistory: List<Pair<String, String>> = emptyList(),
    ): Flow<String> = llmService.generateStream(buildAnswerPrompt(question, citations, plan, recentHistory))

    // ---- Diary Lookup ----

    private suspend fun executeDiaryLookup(
        plan: SearchPlan,
        treeUri: String,
        onStatus: (String) -> Unit,
    ): List<Citation> {
        onStatus("日記ファイルを検索中...")
        val dateStrings = plan.searchKeywords.ifEmpty { DateResolver.resolveToDateStrings("最近") }

        val matched = mutableListOf<DocumentEntity>()
        for (date in dateStrings) {
            matched += documentDao.searchByPath(treeUri, date)
            if (matched.size >= 5) break
        }

        if (matched.isEmpty()) {
            onStatus("関連情報を検索中...")
            return ragPipeline.retrieveTopChunks(plan.searchKeywords.joinToString(" ").ifBlank { "日記" })
        }

        return matched.distinctBy { it.id }.take(5).flatMap { doc ->
            chunkDao.getByDoc(doc.id).take(MAX_CHUNKS_PER_DOC)
                .map { chunk -> Citation(headingPath = chunk.headingPath, snippet = chunk.text, score = 1f) }
        }
    }

    // ---- Topic Research ----

    private suspend fun executeTopicResearch(
        plan: SearchPlan,
        question: String,
        treeUri: String,
        onStatus: (String) -> Unit,
    ): List<Citation> = coroutineScope {
        onStatus("関連ファイルを検索中...")

        val targetDocIds: Set<Long>? = if (plan.targetFolders.isNotEmpty()) {
            plan.targetFolders.flatMap { folder ->
                documentDao.searchByPath(treeUri, folder)
            }.map { it.id }.toSet().takeIf { it.isNotEmpty() }
        } else null

        val keyword = plan.searchKeywords.joinToString(" ").ifBlank { question }

        val bm25Job = async {
            val matchQuery = NGramTokenizer.toFtsMatchQuery(keyword) ?: return@async emptyList<ChunkEntity>()
            runCatching {
                chunkDao.bm25SearchRaw(
                    SimpleSQLiteQuery(
                        """SELECT chunks.* FROM chunks_fts
                           JOIN chunks ON chunks_fts.rowid = chunks.id
                           WHERE chunks_fts MATCH ?
                           LIMIT 50""",
                        arrayOf<Any?>(matchQuery),
                    )
                )
            }.getOrElse { emptyList() }
        }

        val vecJob = async(Dispatchers.Default) {
            val queryVec = embedderService.embed(keyword)
            val pool = if (targetDocIds != null) {
                chunkDao.getAll().filter { it.docId in targetDocIds }
            } else {
                chunkDao.getAll()
            }
            @Suppress("UNCHECKED_CAST")
            CosineSimilarity.topK(
                queryVec,
                pool.map { Pair(EmbedderService.bytesToFloatArray(it.embedding), it) } as List<Pair<FloatArray, Any>>,
                30,
            ).map { (_, meta) -> meta as ChunkEntity }
        }

        val bm25 = bm25Job.await().let { chunks ->
            if (targetDocIds != null) chunks.filter { it.docId in targetDocIds } else chunks
        }
        val vec = vecJob.await()

        val seen = mutableSetOf<Long>()
        (bm25 + vec).filter { seen.add(it.id) }.take(20)
            .map { chunk -> Citation(headingPath = chunk.headingPath, snippet = chunk.text, score = 1f) }
    }

    // ---- File Lookup ----

    private suspend fun executeFileLookup(
        plan: SearchPlan,
        treeUri: String,
        onStatus: (String) -> Unit,
    ): List<Citation> {
        onStatus("ファイルを取得中...")

        val docs = if (plan.targetFiles.isNotEmpty()) {
            plan.targetFiles.flatMap { path ->
                val keyword = path.substringAfterLast("/").removeSuffix(".md")
                documentDao.searchByPath(treeUri, keyword)
            }.distinctBy { it.id }
        } else emptyList()

        if (docs.isEmpty()) {
            onStatus("関連情報を検索中...")
            return ragPipeline.retrieveTopChunks(plan.searchKeywords.joinToString(" ").ifBlank { "general" })
        }

        return docs.take(3).flatMap { doc ->
            chunkDao.getByDoc(doc.id).take(MAX_CHUNKS_PER_DOC)
                .map { chunk -> Citation(headingPath = chunk.headingPath, snippet = chunk.text, score = 1f) }
        }
    }

    // ---- Prompt Builders ----

    private fun buildFolderTree(docs: List<DocumentEntity>): String {
        val counts = mutableMapOf<String, Int>()
        docs.forEach { doc ->
            val folder = doc.relativePath.substringBeforeLast("/", "(root)")
            counts[folder] = (counts[folder] ?: 0) + 1
        }
        return counts.entries.sortedBy { it.key }
            .take(30)
            .joinToString("\n") { (f, n) -> "- $f (${n}件)" }
    }

    private fun buildFileListForPrompt(docs: List<DocumentEntity>): String =
        docs.sortedByDescending { it.lastModified }
            .take(MAX_FILES_IN_PROMPT)
            .joinToString("\n") { doc ->
                val hint = doc.firstParagraph?.take(50)
                    ?: doc.headings?.let {
                        runCatching {
                            val arr = JSONArray(it)
                            (0 until minOf(arr.length(), 2)).joinToString(", ") { i -> arr.getString(i) }
                        }.getOrElse { "" }
                    } ?: ""
                "- ${doc.relativePath}${if (hint.isNotBlank()) ": $hint" else ""}"
            }

    private fun buildPlanPrompt(question: String, folderTree: String, fileList: String): String =
        """あなたは検索プランナーです。質問に最適な検索計画をJSONのみで出力してください。

フォルダ:
$folderTree

ファイル(最新順):
$fileList

質問: $question
intent: diary_lookup/topic_research/file_lookup/general

JSON:
""".trimIndent()

    private fun parsePlanJson(raw: String): SearchPlan? = runCatching {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e < 0) return null
        val obj = JSONObject(raw.substring(s, e + 1))

        fun arr(key: String): List<String> {
            val a = obj.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }

        SearchPlan(
            intent = obj.optString("intent", "general"),
            reasoning = obj.optString("reasoning", ""),
            targetFolders = arr("targetFolders"),
            targetFiles = arr("targetFiles"),
            searchKeywords = arr("searchKeywords"),
        )
    }.getOrNull()

    private fun buildAnswerPrompt(
        question: String,
        citations: List<Citation>,
        plan: SearchPlan,
        history: List<Pair<String, String>>,
    ): String {
        val sourceNote = when (plan.intent) {
            "diary_lookup" -> "（日記・行動記録）"
            "topic_research" -> "（トピック検索）"
            "file_lookup" -> "（ファイル直接参照）"
            else -> ""
        }
        val contextBlock = if (citations.isNotEmpty()) {
            val budgeted = mutableListOf<Citation>()
            var remaining = MAX_CITATION_CHARS
            for (c in citations) {
                val cost = c.headingPath.length + c.snippet.length + 6
                if (remaining <= 0) break
                budgeted += c
                remaining -= cost
            }
            val body = budgeted.joinToString("\n\n") { "### ${it.headingPath}\n${it.snippet}" }
            """あなたはユーザーのパーソナルアシスタントです。以下の「知識ベース$sourceNote」を参考にして質問に答えてください。
知識ベースにある情報を優先し、不足時は一般知識で補足（その際は明記）してください。

知識ベース:
$body

---"""
        } else {
            "知識ベースに関連する情報が見つかりませんでした。一般的な知識で回答してください。\n\n---"
        }

        val historyBlock = history.takeLast(6)
            .joinToString("\n") { (role, content) ->
                "${if (role == "user") "ユーザー" else "アシスタント"}: $content"
            }
            .let { if (it.isNotBlank()) "$it\n" else "" }

        return "$contextBlock\n\n$historyBlock\nユーザー: $question\nアシスタント:"
    }
}
