package com.minibrain.util

/**
 * ドキュメントのファイル名 / パスから拡張子を除いた stem を取り出す。
 * SearchPipeline のファイル名逆引き（topicMatch）・AgentPipeline の plannerHint・
 * ToolExecutor の read_file パス解決で同じ規則を使うため 1 箇所に集約する（ADR-026）。
 */
object FileNames {
    /** `notes/2021-03-15 サウナしきじ.md` → `2021-03-15 サウナしきじ` */
    fun stem(fileNameOrPath: String): String =
        fileNameOrPath.substringAfterLast('/')
            .removeSuffix(".md")
            .removeSuffix(".MD")
}
