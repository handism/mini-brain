package com.minibrain.ai.llm

import com.minibrain.util.JsonArrayText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * JSON 配列だけを返させる呼び出し（QueryExpander / LlmReranker）用。
 * `]` が出た時点で読むのをやめ、それ以降の生成を打ち切る。モデルが配列の後に解説を続けたり、
 * 同じ語を繰り返して maxNumTokens まで止まらなかったりすると 1 件 100 秒かかっていた（ADR-044）。
 *
 * @return 生成されたテキスト。timeoutMs 以内に `]` まで（または生成の終わりまで）届かなければ null
 */
suspend fun LlmService.generateJsonArray(prompt: String, timeoutMs: Long): String? {
    val sb = StringBuilder()
    return withTimeoutOrNull(timeoutMs) {
        collectUntil(prompt, sb) { JsonArrayText.isClosed(it) }
        sb.toString()
    }
}

/**
 * 回答以外の呼び出し（CoverageChecker / Planner / summarize）用。[isDone] が true になるか
 * [timeoutMs] を過ぎたら生成を打ち切り、そこまでのテキストを返す（ADR-066）。
 * 打ち切っても途中のテキストは捨てない。呼び出し側がそのまま解析し、読めなければ従来の既定値に戻す。
 */
suspend fun LlmService.generateBounded(
    prompt: String,
    timeoutMs: Long,
    isDone: (CharSequence) -> Boolean = { false },
): String {
    val sb = StringBuilder()
    withTimeoutOrNull(timeoutMs) { collectUntil(prompt, sb, isDone) }
    return sb.toString()
}

private suspend fun LlmService.collectUntil(prompt: String, sb: StringBuilder, isDone: (CharSequence) -> Boolean) {
    generateStream(prompt)
        .transformWhile { token ->
            sb.append(token)
            emit(Unit)
            !isDone(sb)
        }
        .collect()
}
