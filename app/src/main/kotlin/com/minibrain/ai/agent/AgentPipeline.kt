package com.minibrain.ai.agent

import com.minibrain.ai.agent.tools.ToolExecutor
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class AgentPipeline(
    private val llmService: LlmService,
    private val embedderService: EmbedderService,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
    private val ragPipeline: RagPipeline,
    private val searchPipeline: SearchPipeline,
    private val coverageChecker: CoverageChecker,
) {
    companion object {
        private const val TAG = "AgentPipeline"
        private const val MAX_ITERATIONS = 6
    }

    suspend fun run(
        question: String,
        treeUri: String,
        recentHistory: List<Pair<String, String>> = emptyList(),
        onStatus: ((String) -> Unit)? = null,
    ): AgentResult = withContext(Dispatchers.Default) {
        // 日付範囲はここで一度だけ解決し、classify / search / plannerHint で共有する
        val dateRange = DateResolver.resolveDateRange(question)

        // 一般知識の場合は RAG をスキップして直接 LLM に回答させる
        val queryType = QueryClassifier.classify(question, dateRange = dateRange)
        if (queryType == QueryType.GENERAL_KNOWLEDGE) {
            Timber.tag(TAG).d("GENERAL_KNOWLEDGE — skip RAG")
            return@withContext AgentResult(emptyList(), llmService.generateStream(buildDirectAnswerPrompt(question, recentHistory)))
        }

        // 1 リクエスト分の DB ロードと FloatArray デコードを memoize する。
        // SearchPipeline / RagPipeline / buildPlannerHint で共有することで、
        // 同じ treeUri に対する chunks/documents の重複ロードを完全に排除する（ADR-024）。
        val cache = SearchRequestCache(treeUri, chunkDao, documentDao)

        val traceEvents = mutableListOf<AgentTraceEvent>()

        // --- Search First ---
        val searchResult = searchPipeline.search(question, treeUri, onStatus, dateRange, cache)
        traceEvents += searchResult.traceEvents
        var citations: List<Citation> = searchResult.citations
        Timber.tag(TAG).d("SearchPipeline returned ${citations.size} citations")

        // CoverageCheck: candidates があっても質問に答えられない場合を検出
        var explorerHint: String? = null
        if (citations.isNotEmpty()) {
            onStatus?.invoke("回答可能性を確認中...")
            val coverage = coverageChecker.check(question, citations)
            traceEvents += CoverageCheckEvent(coverage.canAnswer, coverage.missingInformation)
            Timber.tag(TAG).d("CoverageCheck canAnswer=${coverage.canAnswer} missing=${coverage.missingInformation}")
            if (!coverage.canAnswer) {
                val strategy = resolveExplorerStrategy(coverage.missingInformation)
                traceEvents += ExplorerStrategyEvent(strategy.first, strategy.second)
                explorerHint = strategy.third
                Timber.tag(TAG).d("ExplorerStrategy=${strategy.first}")
                citations = emptyList()
            }
        }

        // ReAct ループはフォールバック専用 (SearchPipeline が空 or CoverageCheck 失敗の場合)
        if (citations.isEmpty()) {
            Timber.tag(TAG).d("falling back to ReAct loop (explorerHint=$explorerHint)")
            citations = runReActLoop(question, treeUri, traceEvents, onStatus, explorerHint, dateRange, cache)
        }

        // 最終セーフティネット: RRF 強制実行
        if (citations.isEmpty()) {
            Timber.tag(TAG).d("citations still empty — forced RRF fallback")
            onStatus?.invoke("フォールバック検索中...")
            citations = ragPipeline.retrieveTopChunks(question, treeUri, cache = cache)
            traceEvents += ToolCallEvent(MAX_ITERATIONS + 1, "rrf_search", "\"$question\"")
            traceEvents += ObservationEvent(MAX_ITERATIONS + 1, "${citations.size} citations returned (safety fallback)")
        }

        onStatus?.invoke("")
        val answerFlow = llmService.generateStream(buildAnswerPrompt(question, citations, recentHistory, dateRange))
        AgentResult(citations, answerFlow, traceEvents)
    }

    private data class ExplorerStrategy(val name: String, val reason: String, val hint: String)

    private fun resolveExplorerStrategy(missing: List<String>): Triple<String, String, String> {
        val isTimeRelated = missing.any { it.contains("date") || it.contains("visit") || it.contains("time") || it.contains("when") }
        return if (isTimeRelated) {
            Triple(
                "EXPAND_TIME",
                "missing date info",
                "ファイル本文に日付メタが埋め込まれている可能性が高いです。まず read_file で該当ファイル全文を取得して『初回訪問日』『日付』『date』などのラベル行を確認してください。それでも特定できない場合のみ timeline_search を使ってください。",
            )
        } else {
            Triple("EXPAND_TOPIC", "missing detail", "read_file または grep で詳細内容を調べてください。")
        }
    }

    private suspend fun runReActLoop(
        question: String,
        treeUri: String,
        traceEvents: MutableList<AgentTraceEvent>,
        onStatus: ((String) -> Unit)?,
        explorerHint: String? = null,
        dateRange: DateRange? = null,
        cache: SearchRequestCache,
    ): List<Citation> {
        val executor = ToolExecutor(documentDao, chunkDao, embedderService, ragPipeline, treeUri, llmService, cache)
        val baseHint = buildPlannerHint(question, dateRange, cache)
        val plannerHint = when {
            explorerHint != null && baseHint != null -> "$explorerHint / $baseHint"
            explorerHint != null -> explorerHint
            else -> baseHint
        }
        val observations = mutableListOf<Observation>()
        val toolResults = mutableListOf<ToolResult>()
        var consecutiveParseErrors = 0

        for (iteration in 1..MAX_ITERATIONS) {
            onStatus?.invoke("検索中... (ステップ $iteration/$MAX_ITERATIONS)")
            Timber.tag(TAG).d("ReAct iteration=$iteration hint=$plannerHint obs=${observations.size}")

            if (!llmService.isReady()) {
                Timber.tag(TAG).d("LLM not ready — exit ReAct loop")
                break
            }

            val prompt = PlannerPrompt.build(question, plannerHint, observations)
            val sb = StringBuilder()
            runCatching {
                llmService.generateStream(prompt).collect { token -> sb.append(token) }
            }.onFailure { Timber.tag(TAG).w(it, "planner LLM failed") }

            val decision = PlannerPrompt.parseDecision(sb.toString())
            Timber.tag(TAG).d("decision=$decision raw=${sb.take(200)}")

            when (decision) {
                is PlannerDecision.Finalize -> {
                    Timber.tag(TAG).d("finalize: ${decision.reason}")
                    traceEvents += PlannerDecisionEvent(iteration, "finalize: ${decision.reason}")
                    break
                }
                is PlannerDecision.ParseError -> {
                    consecutiveParseErrors++
                    Timber.tag(TAG).w("parse error ($consecutiveParseErrors)")
                    traceEvents += PlannerDecisionEvent(iteration, "parse_error")
                    if (consecutiveParseErrors >= 2) {
                        Timber.tag(TAG).d("2 consecutive parse errors — RRF fallback")
                        onStatus?.invoke("フォールバック検索中...")
                        val fallbackCitations = ragPipeline.retrieveTopChunks(question, treeUri, cache = cache)
                        val fallbackCall = ToolCall(iteration, AgentTool.RrfSearch(question))
                        toolResults += ToolResult(fallbackCall, "fallback", fallbackCitations)
                        traceEvents += ToolCallEvent(iteration, "rrf_search", "\"$question\"")
                        traceEvents += ObservationEvent(iteration, "${fallbackCitations.size} citations returned")
                        break
                    }
                    continue
                }
                is PlannerDecision.Call -> {
                    consecutiveParseErrors = 0
                    val toolCall = ToolCall(iteration, decision.tool)
                    val tool = decision.tool
                    traceEvents += ToolCallEvent(iteration, tool.traceName, tool.traceArgs)
                    onStatus?.invoke(tool.progressLabel)
                    val result = withContext(Dispatchers.IO) { executor.execute(toolCall) }
                    toolResults += result
                    traceEvents += ObservationEvent(
                        iteration,
                        tool.observationKind(result.citations.size, result.summary.length),
                    )
                    addObservation(observations, toolCall, result.summary)
                    Timber.tag(TAG).d("tool=$tool citations=${result.citations.size}")
                }
            }
        }

        return CitationIntegrator.integrate(toolResults)
    }

    private suspend fun buildPlannerHint(
        question: String,
        dateRange: DateRange?,
        cache: SearchRequestCache,
    ): String? {
        val parts = mutableListOf<String>()
        val allDocs = cache.documents()

        // 期間クエリ: resolveDateRange が成功していたら timeline_search を推奨
        if (dateRange != null) {
            parts += "期間クエリ検出: ${dateRange.start} 〜 ${dateRange.end} / timeline_search を推奨"
        }

        if (dateRange == null && DateResolver.isDiaryQuery(question)) {
            val dates = DateResolver.resolveToDateStrings(question)
            if (dates.isNotEmpty()) {
                parts += "検出された日付: ${dates.joinToString(", ")}"

                val found = mutableListOf<String>()
                val notFound = mutableListOf<String>()
                // パスの正規化 (スラッシュとハイフンを除外) をループ外で一度だけ行うことで高速化
                val normalizedDocs = allDocs.map { doc ->
                    doc to doc.relativePath.replace("/", "").replace("-", "")
                }
                for (date in dates) {
                    // 区切り文字を除いた8桁数字 (YYYYMMDD) でパスを検索
                    val digits = date.replace("-", "")
                    val matches = normalizedDocs.filter { (_, normalizedPath) ->
                        normalizedPath.contains(digits)
                    }.take(3)
                    if (matches.isNotEmpty()) {
                        found += matches.map { (doc, _) -> "[d=${doc.id}] ${doc.relativePath}" }
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
            val name = doc.fileName.removeSuffix(".md").removeSuffix(".MD").lowercase()
            name.length >= 1 && question.lowercase().contains(name)
        }.take(5)
        if (fileMatches.isNotEmpty()) {
            parts += "質問にマッチするファイル候補: ${fileMatches.joinToString(", ") { "[d=${it.id}] ${it.fileName}" }}"
        }

        return parts.joinToString(" / ").ifBlank { null }
    }

    private fun buildHistoryBlock(history: List<Pair<String, String>>): String {
        return history.takeLast(6)
            .joinToString("\n") { (role, content) ->
                "${if (role == "user") "ユーザー" else "アシスタント"}: $content"
            }
            .let { if (it.isNotBlank()) "$it\n" else "" }
    }

    private fun buildContextBlock(citations: List<Citation>): String {
        return if (citations.isNotEmpty()) {
            val budgeted = mutableListOf<Citation>()
            var remainingTokens = TokenEstimator.MAX_CONTEXT_TOKENS
            for (c in citations) {
                val cost = TokenEstimator.estimate(c.headingPath, c.snippet)
                if (remainingTokens <= 0) break
                budgeted += c
                remainingTokens -= cost
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
    }

    private fun buildTemporalInstruction(question: String, citations: List<Citation>, dateRange: DateRange?): String {
        // 日付関連の指示。dateRange があれば期間照合、無くても「いつ」系クエリなら本文中の日付を
        // 拾うよう誘導する（ADR-026）。snippet から日付を抽出する優先順位は次の通り:
        //   1. `[日付: YYYY-MM-DD]` プレフィックス（システム抽出済みの documentDate）
        //   2. 本文中の「初回訪問日: …」「訪問日: …」「日付: …」のようなラベル行
        //   3. 本文中の YYYY/MM/DD / YYYY-MM-DD / YYYY年MM月DD日 表記
        val isDateQuery = DateResolver.isDateQuery(question)
        return when {
            dateRange != null -> {
                val hasDated = citations.any { it.snippet.trimStart().startsWith("[日付:") }
                if (hasDated) {
                    """
【期間クエリの解釈】
質問内の「去年」「先月」「今年の冬」などの相対表現は、システム側で既に **${dateRange.start} 〜 ${dateRange.end}** の期間に解釈済みです。
年号の解釈で迷ったり「どの年を指すか不明」などと逡巡せず、この期間を所与の前提として回答してください。

【回答の作り方】
1. 各 snippet から日付を拾う優先順位:
   - 先頭の `[日付: YYYY-MM-DD]` プレフィックス（システム抽出済みのファイル日付）
   - 本文中の「初回訪問日: …」「訪問日: …」「日付: …」のようなラベル行
   - 本文中の YYYY/MM/DD・YYYY-MM-DD・YYYY年MM月DD日 表記
2. 拾った日付を上記期間と照合し、該当する snippet を時系列順に整理して具体的に答える
3. snippet 本文に活動内容が書かれていれば、それを「情報がない」と切り捨てず素直に紹介する
""".trimIndent()
                } else {
                    """
【期間クエリの解釈】
質問内の相対表現は ${dateRange.start} 〜 ${dateRange.end} の期間に解釈済みです。
`[日付:]` プレフィックス付き候補は見つかりませんでしたが、snippet 本文中の「初回訪問日: …」「訪問日: …」ラベル行や、YYYY/MM/DD・YYYY年MM月DD日 表記も日付として有効です。これらを期間と照合してください。
該当する日付が一切見つからなければ、その旨を率直に伝えたうえで、関連しそうな snippet を補足として提示してください。
""".trimIndent()
                }
            }
            isDateQuery -> {
                """
【日付に関する質問】
質問は時期・日付を尋ねています。snippet から日付を拾う優先順位:
1. 先頭の `[日付: YYYY-MM-DD]` プレフィックス（システム抽出済みのファイル日付）
2. 本文中の「初回訪問日: …」「訪問日: …」「日付: …」のようなラベル行
3. 本文中の YYYY/MM/DD・YYYY-MM-DD・YYYY年MM月DD日 表記

該当ファイル名がクエリに含まれている場合は、そのファイルの本文中の日付を主な根拠として「いつ」かを具体的に答えてください。日付らしき表記が無ければ、その旨を率直に伝えてください。
""".trimIndent()
            }
            else -> ""
        }
    }

    private fun buildDirectAnswerPrompt(
        question: String,
        history: List<Pair<String, String>>,
    ): String {
        val historyBlock = buildHistoryBlock(history)
        return "${historyBlock}ユーザー: $question\nアシスタント:"
    }

    private fun buildAnswerPrompt(
        question: String,
        citations: List<Citation>,
        history: List<Pair<String, String>>,
        dateRange: DateRange? = null,
    ): String {
        val contextBlock = buildContextBlock(citations)
        val temporalInstruction = buildTemporalInstruction(question, citations, dateRange)
        val historyBlock = buildHistoryBlock(history)

        val temporalBlock = if (temporalInstruction.isNotEmpty()) "$temporalInstruction\n\n" else ""
        return "$contextBlock\n\n$temporalBlock$historyBlock\nユーザー: $question\nアシスタント:"
    }

    // observation スライディングウィンドウ: 最新2件を full(詳細)、それ以前を compact(要約)に保つ
    private fun addObservation(observations: MutableList<Observation>, toolCall: ToolCall, summary: String) {
        val isRecent = observations.size < 2
        observations.add(Observation(toolCall, summary, full = isRecent))
        if (observations.size > 2) {
            val idx = observations.size - 3
            if (observations[idx].full) {
                observations[idx] = observations[idx].copy(full = false)
            }
        }
    }


}
