package com.minibrain.ai.rag

import org.json.JSONArray
import org.json.JSONObject

/** チャット履歴（ChatMessageEntity.citationsJson）に保存する Citation の JSON 変換。 */
object CitationJson {

    fun encode(citations: List<Citation>): String = runCatching {
        JSONArray().also { arr ->
            citations.forEach { c ->
                val obj = JSONObject()
                    .put("headingPath", c.headingPath)
                    .put("snippet", c.snippet)
                    .put("score", c.score)
                    .put("source", c.source.name)
                if (c.topicMatch) obj.put("topicMatch", true)
                c.docId?.let { obj.put("docId", it) }
                c.relativePath?.let { obj.put("relativePath", it) }
                arr.put(obj)
            }
        }.toString()
    }.getOrElse { "[]" }

    // 壊れた JSON や古い形式でも履歴表示を落とさないよう、失敗時は空リストを返す
    fun decode(json: String): List<Citation> = runCatching {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            Citation(
                headingPath = obj.getString("headingPath"),
                snippet = obj.getString("snippet"),
                score = obj.optDouble("score", 0.0).toFloat(),
                docId = if (obj.has("docId")) obj.getLong("docId") else null,
                relativePath = obj.optString("relativePath").ifBlank { null },
                source = runCatching { SourceType.valueOf(obj.optString("source")) }.getOrElse { SourceType.UNKNOWN },
                topicMatch = obj.optBoolean("topicMatch", false),
            )
        }
    }.getOrElse { emptyList() }
}
