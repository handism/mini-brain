package com.minibrain.eval

import android.content.Context
import com.minibrain.ai.agent.AgentPipeline
import com.minibrain.ai.agent.SearchTimingEvent
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.ai.search.SearchPipelineResult
import com.minibrain.util.runCatchingCancellable
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.withTimeoutOrNull
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.InputStream

/**
 * 評価セットを SearchPipeline に流し込み、P@K / R@K / MRR を算出する。
 *
 * 呼び出し例（デバッグメニュー等から）:
 * ```kotlin
 * val cases = EvalRunner.load(contentResolver.openInputStream(uri)!!)
 * val result = EvalRunner(searchPipeline).run(treeUri, cases, k = 10)
 * ```
 *
 * 評価セットは個人ノートの実 path を含むためリポジトリには sample のみ置く。
 * 実運用ではユーザーが自分の質問〜正解 path の JSON を作り、設定 → 開発者 →「検索精度の評価」で選ぶ。
 * 既定で測るのは SearchPipeline（展開〜Reranker）までで、CoverageCheck / ReAct は通らない。
 * agentPipeline を渡すと AgentPipeline で回答まで生成し、facts との照合も行う（ADR-048）。
 * このとき検索の指標は AgentPipeline 内の SearchPipeline の結果から取る。
 */
class EvalRunner(
    private val searchPipeline: SearchPipeline,
    private val agentPipeline: AgentPipeline? = null,
) {

    companion object {
        private const val TAG = "EvalRunner"

        // 回答 1 件の上限。CPU の LLM で maxNumTokens まで回り続けても評価全体を止めない
        private const val ANSWER_TIMEOUT_MS = 300_000L
        const val ANSWER_TIMEOUT_MARK = "\n（評価: 回答がタイムアウトしました）"

        /**
         * assets から JSON 配列形式の評価ケースを読む。
         *
         * フォーマット:
         * ```json
         * [
         *   { "id": "case1", "query": "...", "expected": ["folder/note.md", ...], "facts": ["11月3日|11/3", ...] },
         *   ...
         * ]
         * ```
         */
        fun loadFromAssets(context: Context, assetPath: String): List<EvalCase> =
            context.assets.open(assetPath).use(::load)

        /** SAF で選んだファイルなど、任意のストリームから評価ケースを読む。形式は loadFromAssets と同じ。 */
        fun load(stream: InputStream): List<EvalCase> =
            JsonReader.of(stream.source().buffer()).use(::parseArray)

        private fun parseArray(reader: JsonReader): List<EvalCase> {
            val out = mutableListOf<EvalCase>()
            reader.beginArray()
            while (reader.hasNext()) {
                out += parseObject(reader)
            }
            reader.endArray()
            return out
        }

        private fun parseObject(reader: JsonReader): EvalCase {
            var id: String? = null
            var query: String? = null
            var expected: List<String> = emptyList()
            var facts: List<String> = emptyList()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "query" -> query = reader.nextString()
                    "expected" -> expected = parseStringArray(reader)
                    "facts" -> facts = parseStringArray(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(!id.isNullOrBlank() && !query.isNullOrBlank()) {
                "eval case missing id/query"
            }
            return EvalCase(id, query, expected, facts)
        }

        private fun parseStringArray(reader: JsonReader): List<String> {
            val out = mutableListOf<String>()
            reader.beginArray()
            while (reader.hasNext()) out += reader.nextString()
            reader.endArray()
            return out
        }
    }

    suspend fun run(
        treeUri: String,
        cases: List<EvalCase>,
        k: Int = 10,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): EvalResult {
        val observations = cases.mapIndexed { idx, case ->
            onProgress(idx, cases.size)
            val startedAt = System.currentTimeMillis()
            runCatchingCancellable { evaluate(case, treeUri) }
                .fold(
                    onSuccess = { (search, answer) ->
                        EvalObservation(
                            case, search.citations, search.candidates, System.currentTimeMillis() - startedAt,
                            vectorHits = search.vectorHits,
                            timing = search.traceEvents.filterIsInstance<SearchTimingEvent>().firstOrNull(),
                            answer = answer,
                        )
                    },
                    onFailure = {
                        Timber.tag(TAG).w(it, "eval case '${case.id}' failed")
                        EvalObservation(
                            case, emptyList(), emptyList(), System.currentTimeMillis() - startedAt,
                            error = it.message ?: it.javaClass.simpleName,
                        )
                    },
                )
        }
        onProgress(cases.size, cases.size)
        return EvalMetrics.computeObservations(observations, k)
    }

    private suspend fun evaluate(case: EvalCase, treeUri: String): Pair<SearchPipelineResult, String?> {
        val agent = agentPipeline ?: return searchPipeline.search(case.query, treeUri) to null
        val result = agent.run(case.query, treeUri)
        val answer = StringBuilder()
        // タイムアウトは評価全体を止めず、そこまでの回答で採点する
        withTimeoutOrNull(ANSWER_TIMEOUT_MS) { result.answerFlow.collect { answer.append(it) } }
            ?: answer.append(ANSWER_TIMEOUT_MARK)
        // 一般知識と判定されると検索を通らない。検索の指標では「何も拾えなかった」として数える
        val search = result.search ?: SearchPipelineResult(emptyList(), emptyList())
        return search to answer.toString()
    }
}
