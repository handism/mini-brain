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
            return@withContext AgentResult(emptyList(), llmService.generateStream(AnswerPromptBuilder.buildDirectAnswerPrompt(question, recentHistory)))
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
                traceEvents += ExplorerStrategyEvent(strategy.name, strategy.reason)
                explorerHint = strategy.hint
                Timber.tag(TAG).d("ExplorerStrategy=${strategy.name}")
                citations = emptyList()
            }
        }

        // ReAct ループはフォールバック専用 (SearchPipeline が空 or CoverageCheck 失敗の場合)
        if (citations.isEmpty()) {
            Timber.tag(TAG).d("falling back to ReAct loop (explorerHint=$explorerHint)")
            citations = runReActLoop(
                ReActParams(question, treeUri, traceEvents, onStatus, explorerHint, dateRange, cache)
            )
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
        val answerContext = AnswerContext(question, citations, recentHistory, dateRange)
        val answerFlow = llmService.generateStream(AnswerPromptBuilder.buildAnswerPrompt(answerContext))
        AgentResult(citations, answerFlow, traceEvents)
    }

    private data class ExplorerStrategy(val name: String, val reason: String, val hint: String)

    private fun resolveExplorerStrategy(missing: List<String>): ExplorerStrategy {
        val isTimeRelated = missing.any { it.contains("date") || it.contains("visit") || it.contains("time") || it.contains("when") }
        return if (isTimeRelated) {
            ExplorerStrategy(
                "EXPAND_TIME",
                "missing date info",
                "ファイル本文に日付メタが埋め込まれている可能性が高いです。まず read_file で該当ファイル全文を取得して『初回訪問日』『日付』『date』などのラベル行を確認してください。それでも特定できない場合のみ timeline_search を使ってください。",
            )
        } else {
            ExplorerStrategy("EXPAND_TOPIC", "missing detail", "read_file または grep で詳細内容を調べてください。")
        }
    }

    private data class ReActParams(
        val question: String,
        val treeUri: String,
        val traceEvents: MutableList<AgentTraceEvent>,
        val onStatus: ((String) -> Unit)?,
        val explorerHint: String? = null,
        val dateRange: DateRange? = null,
        val cache: SearchRequestCache,
    )

    private suspend fun runReActLoop(params: ReActParams): List<Citation> {
        return ReActLoopExecutor(params).execute()
    }

    private inner class ReActLoopExecutor(
        private val params: ReActParams,
    ) {
        private val executor = ToolExecutor(documentDao, chunkDao, embedderService, ragPipeline, params.treeUri, llmService, params.cache)
        private val observations = mutableListOf<Observation>()
        private val toolResults = mutableListOf<ToolResult>()
        private var consecutiveParseErrors = 0

        suspend fun execute(): List<Citation> {
            val baseHint = PlannerHintBuilder.build(params.question, params.dateRange, params.cache)
            val plannerHint = when {
                params.explorerHint != null && baseHint != null -> "${params.explorerHint} / ${baseHint}"
                params.explorerHint != null -> params.explorerHint
                else -> baseHint
            }

            for (iteration in 1..MAX_ITERATIONS) {
                params.onStatus?.invoke("検索中... (ステップ ${iteration}/${MAX_ITERATIONS})")
                Timber.tag(TAG).d("ReAct iteration=${iteration} hint=${plannerHint} obs=${observations.size}")

                if (!llmService.isReady()) {
                    Timber.tag(TAG).d("LLM not ready — exit ReAct loop")
                    break
                }

                val decision = generateDecision(plannerHint)
                if (handleDecision(decision, iteration)) {
                    break
                }
            }

            return CitationIntegrator.integrate(toolResults)
        }

        private suspend fun generateDecision(plannerHint: String?): PlannerDecision {
            val prompt = PlannerPrompt.build(params.question, plannerHint, observations)
            val sb = StringBuilder()
            runCatching {
                llmService.generateStream(prompt).collect { token -> sb.append(token) }
            }.onFailure { Timber.tag(TAG).w(it, "planner LLM failed") }

            val decision = PlannerPrompt.parseDecision(sb.toString())
            Timber.tag(TAG).d("decision=${decision} raw=${sb.take(200)}")
            return decision
        }

        private suspend fun handleDecision(decision: PlannerDecision, iteration: Int): Boolean {
            return when (decision) {
                is PlannerDecision.Finalize -> {
                    Timber.tag(TAG).d("finalize: ${decision.reason}")
                    params.traceEvents += PlannerDecisionEvent(iteration, "finalize: ${decision.reason}")
                    true
                }
                is PlannerDecision.ParseError -> {
                    handleParseError(iteration)
                }
                is PlannerDecision.Call -> {
                    handleToolCall(decision.tool, iteration)
                    false
                }
            }
        }

        private suspend fun handleParseError(iteration: Int): Boolean {
            consecutiveParseErrors++
            Timber.tag(TAG).w("parse error (${consecutiveParseErrors})")
            params.traceEvents += PlannerDecisionEvent(iteration, "parse_error")
            if (consecutiveParseErrors >= 2) {
                Timber.tag(TAG).d("2 consecutive parse errors — RRF fallback")
                params.onStatus?.invoke("フォールバック検索中...")
                val fallbackCitations = ragPipeline.retrieveTopChunks(params.question, params.treeUri, cache = params.cache)
                val fallbackCall = ToolCall(iteration, AgentTool.RrfSearch(params.question))
                toolResults += ToolResult(fallbackCall, "fallback", fallbackCitations)
                params.traceEvents += ToolCallEvent(iteration, "rrf_search", "\"${params.question}\"")
                params.traceEvents += ObservationEvent(iteration, "${fallbackCitations.size} citations returned")
                return true
            }
            return false
        }

        private suspend fun handleToolCall(tool: AgentTool, iteration: Int) {
            consecutiveParseErrors = 0
            val toolCall = ToolCall(iteration, tool)
            params.traceEvents += ToolCallEvent(iteration, tool.traceName, tool.traceArgs)
            params.onStatus?.invoke(tool.progressLabel)
            val result = withContext(Dispatchers.IO) { executor.execute(toolCall) }
            toolResults += result
            params.traceEvents += ObservationEvent(
                iteration,
                tool.observationKind(result.citations.size, result.summary.length),
            )
            addObservation(observations, toolCall, result.summary)
            Timber.tag(TAG).d("tool=${tool} citations=${result.citations.size}")
        }
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
