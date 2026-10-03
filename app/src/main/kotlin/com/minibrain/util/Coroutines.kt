package com.minibrain.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * suspend 文脈で使う runCatching。標準の runCatching は CancellationException まで
 * Result.failure に包んでしまい、キャンセル後も処理が続いたり「エラー」として表示されたりする。
 * キャンセル（withTimeout のタイムアウトを含む）だけは再送出し、それ以外の例外を Result にする。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
