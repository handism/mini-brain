package com.minibrain.ui.components

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minibrain.R
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.ui.vm.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MessageBubble(msg: ChatMessage, showSearchLog: Boolean) {
    if (msg.role == MessageRole.USER) {
        UserMessageBubble(msg = msg)
    } else {
        AssistantMessageBubble(msg = msg, showSearchLog = showSearchLog)
    }
}

@Composable
fun UserMessageBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
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
fun AssistantMessageBubble(msg: ChatMessage, showSearchLog: Boolean) {
    var citationsExpanded by remember { mutableStateOf(false) }
    var traceExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = Alignment.Start,
        ) {
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
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        MarkdownText(
                            text = msg.content,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // コピーボタン + 引用元（ストリーミング中は非表示）
            if (!msg.isStreaming && msg.content.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MessageCopyButton(content = msg.content)

                    if (msg.citations.isNotEmpty()) {
                        TextButton(onClick = { citationsExpanded = !citationsExpanded }) {
                            Icon(
                                if (citationsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                "引用元 (${msg.citations.size})",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (msg.citations.isNotEmpty() && !msg.isStreaming) {
                AnimatedVisibility(visible = citationsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        msg.citations.forEach { citation ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(8.dp),
                            ) {
                                Column {
                                    Text(
                                        citation.headingPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        citation.snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!msg.isStreaming && showSearchLog && msg.traceEvents.isNotEmpty()) {
                TextButton(
                    onClick = { traceExpanded = !traceExpanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(
                        if (traceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(stringResource(R.string.search_log), style = MaterialTheme.typography.labelSmall)
                }
                AnimatedVisibility(visible = traceExpanded) {
                    AgentTraceSection(msg.traceEvents)
                }
            }
        }
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
            contentDescription = "コピー",
            modifier = Modifier.size(15.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
