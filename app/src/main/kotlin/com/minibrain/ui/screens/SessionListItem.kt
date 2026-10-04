package com.minibrain.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.minibrain.R
import com.minibrain.data.db.entities.ChatSessionSummary
import java.time.LocalDate
import java.time.ZoneId

// 一覧の 1 行に収める回答の冒頭。Markdown の記号は読みの邪魔なので落とす
private val MARKDOWN_SYMBOLS = Regex("[#*_`>|]+")
private val WHITESPACES = Regex("\\s+")

/** 回答の冒頭を一覧用の 1 行にする。回答が無ければ null。 */
internal fun sessionPreview(lastAnswer: String?): String? =
    lastAnswer
        ?.replace(MARKDOWN_SYMBOLS, " ")
        ?.replace(WHITESPACES, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** 履歴一覧・Home の「最近のチャット」で使う 1 行。タイトル（最初の質問）・回答の冒頭・日時・件数を出す。 */
@Composable
internal fun SessionListItem(
    session: ChatSessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    time: String = formatTimestamp(session.updatedAt),
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    ListItem(
        headlineContent = {
            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = sessionPreview(session.lastAnswer)?.let { preview ->
            { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(time, style = MaterialTheme.typography.labelSmall)
                if (session.messageCount > 0) {
                    Text(
                        stringResource(R.string.history_message_count, session.messageCount),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        colors = colors,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** 「今日 14:03」「昨日 9:10」「10/3 8:00」「2025/1/2」のように、日付の区切りが無い場所で使う。 */
@Composable
internal fun formatTimestamp(millis: Long): String =
    when (val label = sessionTimeLabel(millis, LocalDate.now(), ZoneId.systemDefault())) {
        is SessionTimeLabel.Today -> stringResource(R.string.history_today, label.time)
        is SessionTimeLabel.Yesterday -> stringResource(R.string.history_yesterday, label.time)
        is SessionTimeLabel.Date -> label.text
    }
