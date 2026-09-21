@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.ui.components.MessageBubble
import com.minibrain.ui.vm.ChatMessage
import com.minibrain.ui.vm.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    vm: ChatViewModel = viewModel(),
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val showSearchLog by vm.showSearchLog.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新しいメッセージが来たら一番下にスクロール
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                onBack = onBack,
                onOpenHistory = onOpenHistory,
                onNewSession = { vm.newSession() }
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            ChatMessageList(
                messages = messages,
                showSearchLog = showSearchLog,
                listState = listState,
                modifier = Modifier.weight(1f)
            )

            ChatStatusArea(
                isGenerating = isGenerating,
                statusText = statusText,
                errorMessage = errorMessage
            )

            ChatInputArea(
                inputText = inputText,
                isGenerating = isGenerating,
                onValueChange = { inputText = it },
                onSendMessage = { vm.sendMessage(it) },
                onStopGenerating = { vm.cancelGeneration() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewSession: () -> Unit,
) {
    TopAppBar(
        title = { Text("Mini Brain") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
        },
        actions = {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.History, contentDescription = "履歴")
            }
            IconButton(onClick = onNewSession) {
                Icon(Icons.Default.Add, contentDescription = "新しいチャット")
            }
        },
    )
}

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    showSearchLog: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "質問を入力してください\nmdファイルの内容をもとに回答します",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages) { msg -> MessageBubble(msg, showSearchLog) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
fun ChatStatusArea(
    isGenerating: Boolean,
    statusText: String?,
    errorMessage: String?,
) {
    if (isGenerating && statusText != null) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    errorMessage?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
fun ChatInputArea(
    inputText: String,
    isGenerating: Boolean,
    onValueChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGenerating: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("質問を入力...") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
        )

        if (isGenerating) {
            IconButton(onClick = onStopGenerating) {
                Icon(Icons.Default.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            IconButton(
                onClick = {
                    onSendMessage(inputText.trim())
                    onValueChange("")
                },
                enabled = inputText.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
            }
        }
    }
}
