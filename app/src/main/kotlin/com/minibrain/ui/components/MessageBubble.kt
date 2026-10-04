@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.components

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minibrain.R
import com.minibrain.ai.agent.AgentTraceEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.ui.vm.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 固定 dp だとタブレット・横画面で回答が細長くなるので、画面幅に対する割合で決める
private const val BUBBLE_WIDTH_FRACTION = 0.85f
private val BUBBLE_MAX_WIDTH = 720.dp

/**
 * @param statusText 回答の本文が届くまでの間、ストリーミング中の吹き出しに出す進行状況
 * @param onOpenCitation 引用元をタップしたとき。null なら引用元は開けない
 */
@Composable
fun MessageBubble(
    msg: ChatMessage,
    showSearchLog: Boolean,
    statusText: String? = null,
    onOpenCitation: ((Citation) -> Unit)? = null,
) {
    if (msg.role == MessageRole.USER) {
        UserMessageBubble(msg = msg)
    } else {
        AssistantMessageBubble(
            msg = msg,
            showSearchLog = showSearchLog,
            statusText = statusText,
            onOpenCitation = onOpenCitation,
        )
    }
}

@Composable
fun UserMessageBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH).fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            horizontalAlignment = Alignment.End,
        ) {
            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = msg.content,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (!msg.isStreaming && msg.content.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MessageCopyButton(content = msg.content)
                }
            }
        }
    }
}

@Composable
fun AssistantMessageBubble(
    msg: ChatMessage,
    showSearchLog: Boolean,
    statusText: String? = null,
    onOpenCitation: ((Citation) -> Unit)? = null,
) {
    var citationsExpanded by remember { mutableStateOf(false) }
    var traceExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH).fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            horizontalAlignment = Alignment.Start,
        ) {
            AssistantMessageCard(msg = msg, statusText = statusText)

            AssistantMessageActions(
                msg = msg,
                citationsExpanded = citationsExpanded,
                onCitationsExpandedChange = { citationsExpanded = it }
            )

            if (msg.citations.isNotEmpty() && !msg.isStreaming) {
                CitationList(citations = msg.citations, expanded = citationsExpanded, onOpen = onOpenCitation)
            }

            if (!msg.isStreaming && showSearchLog && msg.traceEvents.isNotEmpty()) {
                SearchLogSection(
                    traceEvents = msg.traceEvents,
                    expanded = traceExpanded,
                    onExpandedChange = { traceExpanded = it }
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageCard(msg: ChatMessage, statusText: String?) {
    Card(
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 16.dp,
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (msg.isStreaming && msg.content.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = statusText ?: stringResource(R.string.chat_thinking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MarkdownText(
                    text = msg.content,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageActions(
    msg: ChatMessage,
    citationsExpanded: Boolean,
    onCitationsExpandedChange: (Boolean) -> Unit,
) {
    // コピーボタン + 引用元（ストリーミング中は非表示）
    if (!msg.isStreaming && msg.content.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageCopyButton(content = msg.content)

            if (msg.citations.isNotEmpty()) {
                TextButton(onClick = { onCitationsExpandedChange(!citationsExpanded) }) {
                    Icon(
                        if (citationsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.chat_citations, msg.citations.size),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun CitationList(
    citations: List<Citation>,
    expanded: Boolean,
    onOpen: ((Citation) -> Unit)? = null,
) {
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            citations.forEach { citation ->
                CitationItem(
                    citation = citation,
                    // docId の無い引用（フォルダ要約など）は開く先のファイルが無い
                    onClick = onOpen?.takeIf { citation.docId != null }?.let { open -> { open(citation) } },
                )
            }
        }
    }
}

@Composable
private fun CitationItem(citation: Citation, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(8.dp)
    val fileLabel = citation.relativePath
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (fileLabel != null) {
                Text(
                    text = fileLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // ファイル名と同じ見出しパス（先頭チャンクのヒットなど）は重ねて出さない
            if (citation.headingPath != fileLabel) {
                Text(
                    text = citation.headingPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fileLabel == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = citation.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.chat_open_file),
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SearchLogSection(
    traceEvents: List<AgentTraceEvent>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    TextButton(
        onClick = { onExpandedChange(!expanded) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(text = stringResource(R.string.search_log), style = MaterialTheme.typography.labelSmall)
    }
    AnimatedVisibility(visible = expanded) {
        AgentTraceSection(traceEvents)
    }
}

@Composable
fun MessageCopyButton(content: String) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", content)))
            }
            copied = true
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = stringResource(R.string.copy),
            modifier = Modifier.size(15.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
