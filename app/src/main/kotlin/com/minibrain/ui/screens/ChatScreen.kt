package com.minibrain.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.ai.rag.Citation
import com.minibrain.ui.components.MessageBubble
import com.minibrain.ui.vm.ChatMessage
import com.minibrain.ui.vm.ChatViewModel
import kotlinx.coroutines.launch

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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openFailedMessage = stringResource(R.string.chat_open_file_failed)

    ChatAutoScroll(messages = messages, listState = listState)

    Scaffold(
        topBar = {
            ChatTopBar(
                onBack = onBack,
                onOpenHistory = onOpenHistory,
                onNewSession = { vm.newSession() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                statusText = statusText,
                listState = listState,
                onSuggestionClick = { inputText = it },
                onOpenCitation = { citation ->
                    scope.launch {
                        val uri = vm.citationFileUri(citation)
                        if (uri == null || !openMarkdownFile(context, uri)) {
                            snackbarHostState.showSnackbar(openFailedMessage)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )

            ChatStatusArea(
                // ストリーミング中の吹き出しがあればそちらに出すので、ここでは出さない
                statusText = statusText.takeIf { isGenerating && messages.none { it.isStreaming } },
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

/**
 * 新しいメッセージが来たら一番下へ。ストリーミングで回答が伸びている間は、
 * ユーザーが上へスクロールして読んでいない限り末尾に追従する。
 */
@Composable
private fun ChatAutoScroll(messages: List<ChatMessage>, listState: LazyListState) {
    // 先頭・末尾の Spacer を含めたアイテム数
    val lastIndex = messages.size + 1
    val isNearBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            // 最後のメッセージが一部でも見えていれば「読んでいる最中」とみなす
            lastVisible >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(lastIndex)
    }

    val streamingLength = messages.lastOrNull()?.takeIf { it.isStreaming }?.content?.length
    LaunchedEffect(streamingLength) {
        if (streamingLength != null && isNearBottom) listState.scrollToItem(lastIndex)
    }
}

/** SAF の document URI を外部アプリで開く。開けるアプリが無ければ false。 */
private fun openMarkdownFile(context: Context, fileUri: String): Boolean {
    val uri = Uri.parse(fileUri)
    // text/markdown を扱えるアプリは少ないので、見つからなければ text/plain で開き直す
    for (mimeType in listOf("text/markdown", "text/plain")) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // 次の MIME type を試す
        }
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewSession: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.History, contentDescription = stringResource(R.string.chat_history))
            }
            IconButton(onClick = onNewSession) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_new))
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
    statusText: String? = null,
    onSuggestionClick: (String) -> Unit = {},
    onOpenCitation: ((Citation) -> Unit)? = null,
) {
    if (messages.isEmpty()) {
        ChatEmptyState(onSuggestionClick = onSuggestionClick, modifier = modifier)
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages) { msg ->
                MessageBubble(
                    msg = msg,
                    showSearchLog = showSearchLog,
                    statusText = statusText.takeIf { msg.isStreaming },
                    onOpenCitation = onOpenCitation,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.chat_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.chat_suggestions_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // タップしたら入力欄に入れるだけにして、固有名詞などを書き換えてから送れるようにする
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            stringArrayResource(R.array.chat_suggestions).forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestionClick(suggestion) },
                    label = { Text(suggestion) },
                )
            }
        }
    }
}

@Composable
fun ChatStatusArea(
    statusText: String?,
    errorMessage: String?,
) {
    statusText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    errorMessage?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
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
    val canSend = !isGenerating && inputText.isNotBlank()
    val send = {
        if (canSend) {
            onSendMessage(inputText.trim())
            onValueChange("")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
        )

        if (isGenerating) {
            IconButton(onClick = onStopGenerating) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = send, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}
