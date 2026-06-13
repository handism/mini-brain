package com.minibrain.eval

// 評価セットの 1 ケース。
//
// expectedRelativePaths は「この質問に対して上位 K 件に含まれるべきファイル」のリスト。
// Citation.relativePath との完全一致で判定する（大文字小文字無視）。
// docId ではなく relativePath を採用するのは、再インデックスや DB 入れ替えで docId が変化しても
// 評価セットを使い回せるようにするため。
data class EvalCase(
    val id: String,
    val query: String,
    val expectedRelativePaths: List<String>,
)
