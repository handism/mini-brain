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
        generateStream(prompt)
            .transformWhile { token ->
                sb.append(token)
                emit(Unit)
                !JsonArrayText.isClosed(sb)
            }
            .collect()
        sb.toString()
    }
}
