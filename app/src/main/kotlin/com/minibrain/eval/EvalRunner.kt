package com.minibrain.eval

import android.content.Context
import com.minibrain.ai.search.SearchPipeline
import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import timber.log.Timber

// 評価セットを SearchPipeline に流し込み、P@K / R@K / MRR を算出する。
// 呼び出し例（デバッグメニュー等から）:
//   val cases = EvalRunner.loadFromAssets(context, "eval/queries.sample.json")
//   val result = EvalRunner(searchPipeline).run(treeUri, cases, k = 10)
//
// 評価セットは個人ノートの実 path を含むためリポジトリには sample のみ置く。
// 実運用ではユーザーが自分の質問〜正解 path セットを足して使う想定。
class EvalRunner(private val searchPipeline: SearchPipeline) {

    companion object {
        private const val TAG = "EvalRunner"

        // assets から JSON 配列形式の評価ケースを読む。
        // フォーマット:
        // [
        //   { "id": "case1", "query": "...", "expected": ["folder/note.md", ...] },
        //   ...
        // ]
        fun loadFromAssets(context: Context, assetPath: String): List<EvalCase> {
            return context.assets.open(assetPath).use { stream ->
                JsonReader.of(stream.source().buffer()).use { reader ->
                    parseArray(reader)
                }
            }
        }

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
        val collected = mutableListOf<Pair<EvalCase, List<com.minibrain.ai.rag.Citation>>>()
        cases.forEachIndexed { idx, case ->
            onProgress(idx, cases.size)
            val result = runCatching {
                searchPipeline.search(case.query, treeUri).citations
            }.onFailure { Timber.tag(TAG).w(it, "eval case '${case.id}' failed") }
                .getOrDefault(emptyList())
            collected += case to result
        }
        onProgress(cases.size, cases.size)
        return EvalMetrics.compute(collected, k)
    }
}
