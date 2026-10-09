package com.minibrain.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.ai.rag.Citation
import com.minibrain.ui.components.CHAT_CONTENT_MAX_WIDTH
import com.minibrain.ui.components.MessageBubble
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.data.repo.IndexingState
import com.minibrain.ui.vm.ChatError
import com.minibrain.ui.vm.ChatErrorKind
import com.minibrain.ui.vm.ChatMessage
import com.minibrain.ui.vm.ChatViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    vm: ChatViewModel = viewModel(),
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val indexingState by vm.indexingState.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val showSearchLog by vm.showSearchLog.collectAsStateWithLifecycle()
    val sessionTitle by vm.sessionTitle.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val treeUri by vm.savedTreeUri.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openFailedMessage = stringResource(R.string.chat_open_file_failed)
    val inputFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val isNearBottom = rememberIsNearBottom(listState)
    ChatAutoScroll(messages = messages, listState = listState, isNearBottom = isNearBottom)

    Scaffold(
        topBar = {
            ChatTopBar(
                title = sessionTitle,
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
                // Scaffold が足したナビゲーションバー分を消費しないと、キーボード表示時に隙間が二重になる
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            treeUri?.let { uri ->
                Text(
                    stringResource(R.string.chat_reference_folder, com.minibrain.ui.components.folderDisplayName(uri)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            (indexingState as? IndexingState.Progress)?.let { ChatIndexingBanner(it) }

            ChatMessageList(
                messages = messages,
                showSearchLog = showSearchLog,
                statusText = statusText,
                listState = listState,
                suggestions = suggestions,
                // 固有名詞などを書き換えてから送れるよう、入力欄に入れてキーボードを出す
                onSuggestionClick = {
                    inputText = it
                    inputFocusRequester.requestFocus()
                    keyboard?.show()
                },
                onRegenerate = { vm.regenerate() }.takeUnless { isGenerating },
                onOpenCitation = { citation ->
                    scope.launch {
                        val uri = vm.citationFileUri(citation)
                        if (uri == null || !openMarkdownFile(context, uri)) {
                            snackbarHostState.showSnackbar(openFailedMessage)
                        }
                    }
                },
                isNearBottom = isNearBottom.value,
                modifier = Modifier.weight(1f)
            )

            // 生成中の段階は回答欄（GenerationProgress）にだけ出す。ここは失敗の表示だけ
            ChatStatusArea(
                error = error,
                onRetry = { vm.regenerate() }.takeUnless { isGenerating },
                onDismissError = { vm.dismissError() },
            )

            ChatInputArea(
                inputText = inputText,
                isGenerating = isGenerating,
                onValueChange = { inputText = it },
                onSendMessage = { vm.sendMessage(it) },
                onStopGenerating = { vm.cancelGeneration() },
                focusRequester = inputFocusRequester,
            )
        }
    }
}

/**
 * 新しいメッセージが来たら一番下へ。ストリーミングで回答が伸びている間は、
 * ユーザーが上へスクロールして読んでいない限り末尾に追従する。
 */
@Composable
private fun ChatAutoScroll(messages: List<ChatMessage>, listState: LazyListState, isNearBottom: State<Boolean>) {
    // 先頭・末尾の Spacer を含めたアイテム数
    val lastIndex = messages.size + 1

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(lastIndex)
    }

    val streamingLength = messages.lastOrNull()?.takeIf { it.isStreaming }?.content?.length
    LaunchedEffect(streamingLength) {
        if (streamingLength != null && isNearBottom.value) listState.scrollToItem(lastIndex)
    }
}

/** 最後のメッセージが一部でも見えていれば「読んでいる最中」とみなす。 */
@Composable
private fun rememberIsNearBottom(listState: LazyListState): State<Boolean> = remember(listState) {
    derivedStateOf {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
        lastVisible >= info.totalItemsCount - 2
    }
}

/** 起動直後はチャットから始まり Home の進捗が見えないので、インデックス中はここにも出す。 */
@Composable
private fun ChatIndexingBanner(state: IndexingState.Progress) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.chat_indexing_banner, state.current, state.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.current.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
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
    title: String?,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewSession: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                title ?: stringResource(R.string.app_name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
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
    suggestions: List<String> = emptyList(),
    onSuggestionClick: (String) -> Unit = {},
    onOpenCitation: ((Citation) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    isNearBottom: Boolean = true,
) {
    if (messages.isEmpty()) {
        ChatEmptyState(suggestions = suggestions, onSuggestionClick = onSuggestionClick, modifier = modifier)
        return
    }
    val scope = rememberCoroutineScope()
    // ストリーミングに追従している間は出さない（追従が止まるのは上へスクロールしたときだけ）
    val streaming = messages.lastOrNull()?.isStreaming == true
    val showJumpToBottom = listState.canScrollForward && !(streaming && isNearBottom)
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            itemsIndexed(messages) { index, msg ->
                // タブレット・横画面でも読みやすい幅に収め、中央に寄せる
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    MessageBubble(
                        msg = msg,
                        showSearchLog = showSearchLog,
                        statusText = statusText.takeIf { msg.isStreaming },
                        onOpenCitation = onOpenCitation,
                        // 再生成できるのは最後の回答だけ
                        onRegenerate = onRegenerate?.takeIf {
                            index == messages.lastIndex && msg.role == MessageRole.ASSISTANT
                        },
                        modifier = Modifier.widthIn(max = CHAT_CONTENT_MAX_WIDTH),
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        AnimatedVisibility(
            visible = showJumpToBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(messages.size + 1) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_scroll_to_bottom))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyState(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_CONTENT_MAX_WIDTH)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
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
            stringResource(R.string.chat_suggestion_edit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // タップしたら入力欄に入れるだけにして、固有名詞などを書き換えてから送れるようにする
        val periodSuggestions = setOf(
            stringResource(R.string.chat_suggestion_last_month),
            stringResource(R.string.chat_suggestion_this_month),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                androidx.compose.material3.Card(
                    onClick = { onSuggestionClick(suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(if (suggestion in periodSuggestions) R.string.chat_suggestion_reflect else R.string.chat_suggestion_explore),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(suggestion, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatStatusArea(
    error: ChatError?,
    onRetry: (() -> Unit)?,
    onDismissError: () -> Unit,
) {
    error?.let {
        ChatErrorCard(
            error = it,
            onRetry = onRetry,
            onDismiss = onDismissError,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = CHAT_CONTENT_MAX_WIDTH)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 失敗したことと、やり直す手段を出す。例外の中身は利用者には読めないことが多いので折りたたむ。 */
@Composable
private fun ChatErrorCard(
    error: ChatError,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember(error) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(
                        when (error.kind) {
                            ChatErrorKind.SEARCH -> R.string.chat_error_search
                            ChatErrorKind.GENERATION -> R.string.chat_error_generation
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(18.dp))
                }
            }
            if (showDetail && !error.detail.isNullOrBlank()) {
                SelectionContainer {
                    Text(
                        error.detail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                    )
                }
            }
            Row {
                if (!error.detail.isNullOrBlank()) {
                    TextButton(onClick = { showDetail = !showDetail }) {
                        Text(stringResource(if (showDetail) R.string.hide_detail else R.string.show_detail))
                    }
                }
                onRetry?.let { retry ->
                    TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

@Composable
fun ChatInputArea(
    inputText: String,
    isGenerating: Boolean,
    onValueChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGenerating: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    // 質問例のように外から文字が入ったときは、カーソルを末尾に置いて続きを書けるようにする
    var fieldValue by remember { mutableStateOf(TextFieldValue(inputText, TextRange(inputText.length))) }
    if (fieldValue.text != inputText) {
        fieldValue = TextFieldValue(inputText, TextRange(inputText.length))
    }
    val canSend = !isGenerating && inputText.isNotBlank()
    val haptic = LocalHapticFeedback.current
    val send = {
        if (canSend) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onSendMessage(inputText.trim())
            onValueChange("")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_CONTENT_MAX_WIDTH)
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 枠線ではなく塗りのピル型にして、入力欄を画面下の操作面としてまとめる
        TextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onValueChange(it.text)
            },
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 5,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )

        // 入力欄（1 行時 56dp）と高さを揃える
        val buttonModifier = Modifier.size(56.dp)
        if (isGenerating) {
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    onStopGenerating()
                },
                modifier = buttonModifier,
            ) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.chat_stop))
            }
        } else {
            FilledIconButton(onClick = send, enabled = canSend, modifier = buttonModifier) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}
