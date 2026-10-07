package com.minibrain.eval

/**
 * 評価セットの 1 ケース。
 *
 * expectedRelativePaths は「この質問に対して上位 K 件に含まれるべきファイル」のリスト。
 * Citation.relativePath との完全一致で判定する（大文字小文字無視）。
 * docId ではなく relativePath を採用するのは、再インデックスや DB 入れ替えで docId が変化しても
 * 評価セットを使い回せるようにするため。
 *
 * facts は「回答に含まれるべき事実」。1 要素が 1 つの事実で、`|` で区切った表記ゆれのどれかが
 * 回答に含まれれば当たりとする（ADR-048）。空なら回答の評価から外す。
 *
 * unanswerable は知識ベースに答えが無い質問。検索の指標から外し、回答で「見つからない」と
 * 答えられたか（作り話をしなかったか）だけを数える（ADR-054）。
 */
data class EvalCase(
    val id: String,
    val query: String,
    val expectedRelativePaths: List<String>,
    val facts: List<String> = emptyList(),
    val unanswerable: Boolean = false,
)
