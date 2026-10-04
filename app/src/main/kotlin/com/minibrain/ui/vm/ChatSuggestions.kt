package com.minibrain.ui.vm

import java.time.LocalDate
import java.time.YearMonth

/** 空のチャットに出す質問例の文言。リソースから渡す（JVM テストで Context を要らなくするため）。 */
internal data class SuggestionTexts(
    val lastMonth: String,
    val thisMonth: String,
    val topic: (String) -> String,
    val fallback: List<String>,
)

// 日付だけのファイル名（2026-10-01.md, 202610 など）は話題にならないので候補から外す
private val DATE_LIKE_STEM = Regex("^[\\d\\s\\-_./年月日]+$")
private const val MAX_TOPIC_SUGGESTIONS = 2
private const val MAX_TOPIC_CHARS = 20

/**
 * 知識ベースの中身から質問例を作る。
 * - 先月（無ければ今月）の日付を持つ文書があれば期間の要約
 * - 最近更新したファイル名から話題を最大 2 件
 * 何も作れなければ固定の例文、作れたら末尾に固定の例文を 1 つ足す。
 *
 * @param documentDates 文書の `documentDate`（`YYYY-MM-DD`）
 * @param recentFileNames 更新日時の新しい順のファイル名
 */
internal fun buildChatSuggestions(
    documentDates: List<String>,
    recentFileNames: List<String>,
    today: LocalDate,
    texts: SuggestionTexts,
): List<String> {
    val months = documentDates.mapNotNull { date ->
        runCatching { YearMonth.from(LocalDate.parse(date)) }.getOrNull()
    }.toSet()
    val thisMonth = YearMonth.from(today)
    val period = when {
        thisMonth.minusMonths(1) in months -> texts.lastMonth
        thisMonth in months -> texts.thisMonth
        else -> null
    }

    val topics = recentFileNames
        .map { it.substringBeforeLast('.').trim() }
        .filter { it.length in 2..MAX_TOPIC_CHARS && !it.matches(DATE_LIKE_STEM) }
        .distinct()
        .take(MAX_TOPIC_SUGGESTIONS)
        .map(texts.topic)

    val dynamic = listOfNotNull(period) + topics
    if (dynamic.isEmpty()) return texts.fallback
    return dynamic + texts.fallback.take(1)
}
