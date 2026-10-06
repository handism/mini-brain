package com.minibrain.eval

import android.content.Context
import com.minibrain.ai.agent.SearchTimingEvent
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.util.runCatchingCancellable
import com.squareup.moshi.JsonReader
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
 * 測るのは SearchPipeline（展開〜Reranker）までで、CoverageCheck / ReAct は通らない。
 */
class EvalRunner(private val searchPipeline: SearchPipeline) {

    companion object {
        private const val TAG = "EvalRunner"

        /**
         * assets から JSON 配列形式の評価ケースを読む。
         *
         * フォーマット:
         * ```json
         * [
         *   { "id": "case1", "query": "...", "expected": ["folder/note.md", ...] },
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
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "query" -> query = reader.nextString()
                    "expected" -> expected = parseStringArray(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(!id.isNullOrBlank() && !query.isNullOrBlank()) {
                "eval case missing id/query"
            }
            return EvalCase(id, query, expected)
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
            runCatchingCancellable { searchPipeline.search(case.query, treeUri) }
                .fold(
                    onSuccess = {
                        EvalObservation(
                            case, it.citations, it.candidates, System.currentTimeMillis() - startedAt,
                            vectorHits = it.vectorHits,
                            timing = it.traceEvents.filterIsInstance<SearchTimingEvent>().firstOrNull(),
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
}
