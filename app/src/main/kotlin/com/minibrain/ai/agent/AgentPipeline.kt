package com.minibrain.ai.agent

import android.util.Log
import com.minibrain.ai.agent.tools.ToolExecutor
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentPipeline(
    private val llmService: LlmService,
    private val embedderService: EmbedderService,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
    private val ragPipeline: RagPipeline,
) {
    companion object {
        private const val TAG = "AgentPipeline"
        private const val MAX_ITERATIONS = 6
    }

    suspend fun run(
        question: String,
        treeUri: String,
        recentHistory: List<Pair<String, String>> = emptyList(),
        onStatus: (String) -> Unit = {},
    ): AgentResult = withContext(Dispatchers.Default) {
        val executor = ToolExecutor(documentDao, chunkDao, embedderService, ragPipeline, treeUri)
        val plannerHint = buildPlannerHint(question, treeUri)
        val observations = mutableListOf<Observation>()
        val toolResults = mutableListOf<ToolResult>()
        var consecutiveParseErrors = 0

        for (iteration in 1..MAX_ITERATIONS) {
            onStatus("検索中... (ステップ $iteration/$MAX_ITERATIONS)")
            Log.d(TAG, "iteration=$iteration hint=$plannerHint obs=${observations.size}")

            if (!llmService.isReady()) {
                Log.d(TAG, "LLM not ready — fallback")
                break
            }

            val prompt = PlannerPrompt.build(question, plannerHint, observations)
            val sb = StringBuilder()
            runCatching {
                llmService.generateStream(prompt).collect { token -> sb.append(token) }
            }.onFailure { Log.w(TAG, "planner LLM failed: ${it.message}") }

            val decision = PlannerPrompt.parseDecision(sb.toString())
            Log.d(TAG, "decision=$decision raw=${sb.take(200)}")

            when (decision) {
                is PlannerDecision.Finalize -> {
                    Log.d(TAG, "finalize: ${decision.reason}")
                    break
                }
                is PlannerDecision.ParseError -> {
                    consecutiveParseErrors++
                    Log.w(TAG, "parse error ($consecutiveParseErrors)")
                    if (consecutiveParseErrors >= 2) {
                        Log.d(TAG, "2 consecutive parse errors — RRF fallback")
                        onStatus("フォールバック検索中...")
                        val fallbackCitations = ragPipeline.retrieveTopChunks(question, treeUri)
                        val fallbackCall = ToolCall(iteration, AgentTool.RrfSearch(question))
                        toolResults += ToolResult(fallbackCall, "fallback", fallbackCitations)
                        break
                    }
                    continue
                }
                is PlannerDecision.Call -> {
                    consecutiveParseErrors = 0
                    val toolCall = ToolCall(iteration, decision.tool)
                    onStatus(toolProgressDescription(decision.tool))
                    val result = withContext(Dispatchers.IO) { executor.execute(toolCall) }
                    toolResults += result

                    val isRecent = observations.size < 2
                    val obs = Observation(toolCall, result.summary, full = isRecent)
                    observations.add(obs)

                    // 全 observation を最新2件だけ full にする
                    if (observations.size > 2) {
                        val idx = observations.size - 3
                        if (observations[idx].full) {
                            observations[idx] = observations[idx].copy(full = false)
                        }
                    }

                    Log.d(TAG, "tool=${decision.tool} citations=${result.citations.size} obsLen=${result.summary.length}")
                }
            }
        }

        var citations = CitationIntegrator.integrate(toolResults)
        Log.d(TAG, "total citations=${citations.size}")

        // セーフティネット: ツールが何も見つけられなかった場合は必ず RRF 検索を実行
        if (citations.isEmpty()) {
            Log.d(TAG, "citations empty after loop — forced RRF fallback")
            onStatus("フォールバック検索中...")
            citations = ragPipeline.retrieveTopChunks(question, treeUri)
            Log.d(TAG, "fallback citations=${citations.size}")
        }

        onStatus("")
        val answerFlow = llmService.generateStream(buildAnswerPrompt(question, citations, recentHistory))
        AgentResult(citations, answerFlow)
    }

    private suspend fun buildPlannerHint(question: String, treeUri: String): String? {
        val parts = mutableListOf<String>()
        val allDocs = withContext(Dispatchers.IO) { documentDao.getAllByTree(treeUri) }

        if (DateResolver.isDiaryQuery(question)) {
            val dates = DateResolver.resolveToDateStrings(question)
            if (dates.isNotEmpty()) {
                parts += "検出された日付: ${dates.joinToString(", ")}"

                val found = mutableListOf<String>()
                val notFound = mutableListOf<String>()
                for (date in dates) {
                    // 区切り文字を除いた8桁数字 (YYYYMMDD) でパスを検索
                    val digits = date.replace("-", "")
                    val matches = allDocs.filter { doc ->
                        doc.relativePath.replace("/", "").replace("-", "").contains(digits)
                    }.take(3)
                    if (matches.isNotEmpty()) {
                        found += matches.map { "[d=${it.id}] ${it.relativePath}" }
                    } else {
                        notFound += date
                    }
                }
                if (found.isNotEmpty()) {
                    parts += "日付に一致するファイル: ${found.joinToString(", ")}"
                }
                if (notFound.isNotEmpty()) {
                    // 見つからない場合は YYYYMMDD / YYYY/MM/DD / YYYY-MM-DD の3形式を列挙
                    val globs = notFound.flatMap { date ->
                        listOf(
                            "\"${date.replace("-", "")}*\"",
                            "\"${date.replace("-", "/")}*\"",
                            "\"$date*\"",
                        )
                    }
                    parts += "推奨 glob パターン: ${globs.joinToString(" or ")}"
                }
            }
        }

        val fileMatches = allDocs.filter { doc ->
            val name = doc.fileName.removeSuffix(".md").lowercase()
            name.length >= 2 && question.lowercase().contains(name)
        }.take(5)
        if (fileMatches.isNotEmpty()) {
            parts += "質問にマッチするファイル候補: ${fileMatches.joinToString(", ") { "[d=${it.id}] ${it.fileName}" }}"
        }

        return parts.joinToString(" / ").ifBlank { null }
    }

    private fun toolProgressDescription(tool: AgentTool): String = when (tool) {
        is AgentTool.Glob -> "ファイルパターン検索中..."
        is AgentTool.ListDir -> "フォルダ一覧取得中..."
        is AgentTool.ReadFile -> "ファイル読込中..."
        is AgentTool.Grep -> "キーワード検索中..."
        is AgentTool.VectorSearch -> "ベクトル検索中..."
        is AgentTool.RrfSearch -> "ハイブリッド検索中..."
    }

    private fun buildAnswerPrompt(
        question: String,
        citations: List<Citation>,
        history: List<Pair<String, String>>,
    ): String {
        val MAX_CITATION_CHARS = 4000
        val contextBlock = if (citations.isNotEmpty()) {
            val budgeted = mutableListOf<Citation>()
            var remaining = MAX_CITATION_CHARS
            for (c in citations) {
                val cost = c.headingPath.length + c.snippet.length + 6
                if (remaining <= 0) break
                budgeted += c
                remaining -= cost
            }
            val body = budgeted.joinToString("\n\n") { c ->
                val pathPrefix = c.relativePath?.let { "$it > " } ?: ""
                "### $pathPrefix${c.headingPath}\n${c.snippet}"
            }
            """あなたはユーザーのパーソナルアシスタントです。以下の「知識ベース」を参考にして質問に答えてください。
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
