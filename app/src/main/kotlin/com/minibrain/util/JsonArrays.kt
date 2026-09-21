package com.minibrain.util

import org.json.JSONArray
import timber.log.Timber

/**
 * DB に保存した JSON 配列文字列（`documents.headings` / `documents.tags`）を読み戻すヘルパ。
 * 壊れた値が入っていても検索フロー全体を落とさないよう、失敗時は空リストにフォールバックする。
 */
object JsonArrays {
    fun toStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i) }
        }.onFailure { Timber.tag("JsonArrays").w(it, "parse failed: $json") }
            .getOrElse { emptyList() }
    }
}
