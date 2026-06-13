package com.minibrain.ai.rag

/**
 * 重複排除キー。SearchPipeline の RRF 融合 / dateRange pin / multiVectorSearch dedupe で
 * 同一の (docId, headingPath) ペアを 1 件として扱うときに使う。
 * フォーマットを変える場合はここだけ触れば良い。
 */
val Citation.dedupeKey: String
    get() = "$docId::$headingPath"
