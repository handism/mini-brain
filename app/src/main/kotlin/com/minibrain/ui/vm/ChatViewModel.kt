package com.minibrain.ui.vm

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.data.repo.IndexingState
import com.minibrain.R
import com.minibrain.ai.agent.AgentResult
import com.minibrain.ai.agent.AgentTraceEvent
import com.minibrain.ai.agent.FinalAnswerEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.CitationJson
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.dataStore
import com.minibrain.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val PREF_TREE_URI = stringPreferencesKey("tree_uri")
private val PREF_SHOW_SEARCH_LOG = booleanPreferencesKey("show_search_log")

data class ChatMessage(
    val id: Long = 0,
    val role: MessageRole,
    val content: String,
    val citations: List<Citation> = emptyList(),
    val isStreaming: Boolean = false,
    val traceEvents: List<AgentTraceEvent> = emptyList(),
)

enum class ChatErrorKind { SEARCH, GENERATION }

/** 画面に出す失敗。見出しは [kind] から画面側で決め、例外の中身は [detail] として折りたたんで出す。 */
data class ChatError(val kind: ChatErrorKind, val detail: String?)

class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp
    private val navSessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error

    /** チャット中もインデックスの進み具合を出すため（起動時は Home を経由しないので）。 */
    val indexingState: StateFlow<IndexingState> = app.container.documentRepository.indexingState

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText

    val savedTreeUri: StateFlow<String?> = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showSearchLog: StateFlow<Boolean> = app.dataStore.data
        .map { prefs -> prefs[PREF_SHOW_SEARCH_LOG] ?: SHOW_SEARCH_LOG_DEFAULT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SHOW_SEARCH_LOG_DEFAULT)

    /** 空のチャットに出す質問例。知識ベースから作れるまでは固定の例文。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<List<String>> = savedTreeUri
        .mapLatest { treeUri -> treeUri?.takeIf { it.isNotEmpty() }?.let { loadSuggestions(it) } ?: suggestionTexts().fallback }
        .stateIn(viewModelScope, SharingStarted.Eagerly, suggestionTexts().fallback)

    private val _sessionId = MutableStateFlow<Long>(-1)

    /** トップバーに出すセッション名。セッション確定前は null。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionTitle: StateFlow<String?> = _sessionId
        .flatMapLatest { id ->
            if (id == -1L) flowOf(null) else app.container.chatRepository.observeSessionTitle(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var currentJob: Job? = null

    init {
        viewModelScope.launch {
            _sessionId.value = if (navSessionId > 0L) navSessionId
                               else app.container.chatRepository.getOrCreateSession()
        }

        viewModelScope.launch {
            _sessionId.collectLatest { id ->
                if (id == -1L) return@collectLatest
                app.container.chatRepository.observeMessages(id).collect { entities ->
                    if (!_isGenerating.value) {
                        val existingTrace = _messages.value.associate { it.id to it.traceEvents }
                        _messages.value = entities.map { entity ->
                            ChatMessage(
                                id = entity.id,
                                role = entity.role,
                                content = entity.content,
                                citations = CitationJson.decode(entity.citationsJson),
                                traceEvents = existingTrace[entity.id] ?: emptyList(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun sendMessage(question: String) {
        if (question.isBlank() || _isGenerating.value) return

        currentJob?.cancel()
        // launch 前に立てて、ディスパッチ待ちの間に二重送信されないようにする
        _isGenerating.value = true
        _error.value = null
        _statusText.value = null
        // viewModelScope は Main.immediate なので、currentJob を代入してから本体を走らせる
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                setupUserAndStreamingMessages(question)

                // エージェントループ（計画 → 多段ツール実行 → 回答）
                val agentResult = runAgentPipeline(question) ?: return@launch
                val finalContent = collectAnswerStream(agentResult.answerFlow, agentResult.citations)

                finalizeMessage(finalContent, agentResult.citations, agentResult.traceEvents)
            } finally {
                // 途中で例外が出ても送信不能のまま固まらないよう必ず戻す。
                // キャンセル後に次の送信が始まっていたら、そちらの状態は触らない
                if (currentJob === coroutineContext[Job]) {
                    _isGenerating.value = false
                    _statusText.value = null
                }
            }
        }
        currentJob = job
        job.start()
    }

    private suspend fun setupUserAndStreamingMessages(question: String) {
        // ユーザーメッセージを追加（最初の送信時はセッションタイトルを質問で更新）
        if (_messages.value.isEmpty()) {
            val title = question.take(40).let { if (question.length > 40) "$it…" else it }
            app.container.chatRepository.updateSessionTitle(_sessionId.value, title)
        }
        val userMsg = ChatMessage(role = MessageRole.USER, content = question)
        _messages.value = _messages.value + userMsg
        app.container.chatRepository.addMessage(_sessionId.value, MessageRole.USER, question)

        // ストリーミングプレースホルダーを先行追加（検索中も CircularProgressIndicator 表示）
        val streamingMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
        )
        _messages.value = _messages.value + streamingMsg
    }

    private suspend fun runAgentPipeline(question: String): AgentResult? {
        val treeUri = savedTreeUri.value ?: ""
        val history = app.container.chatRepository.getRecentHistory(_sessionId.value).map { msg ->
            Pair(msg.role.name.lowercase(), msg.content)
        }

        return runCatchingCancellable {
            app.container.agentPipeline.run(question, treeUri, history) { status ->
                _statusText.value = status.ifBlank { null }
            }
        }.getOrElse {
            _error.value = ChatError(ChatErrorKind.SEARCH, it.message)
            removeStreamingMessage()
            null
        }
    }

    private suspend fun collectAnswerStream(answerFlow: Flow<String>, citations: List<Citation>): String {
        _statusText.value = null

        // 引用元をストリーミングメッセージに反映
        updateStreamingMessage { it.copy(citations = citations) }

        val sb = StringBuilder()
        runCatchingCancellable {
            answerFlow.collect { token ->
                sb.append(token)
                val currentContent = sb.toString()
                updateStreamingMessage {
                    val shouldHide = isNegativeResponse(currentContent)
                    it.copy(
                        content = currentContent,
                        citations = if (shouldHide) emptyList() else citations,
                    )
                }
            }
        }.onFailure {
            _error.value = ChatError(ChatErrorKind.GENERATION, it.message)
        }
        return sb.toString()
    }

    // ストリーミング完了
    private suspend fun finalizeMessage(finalContent: String, citations: List<Citation>, traceEvents: List<AgentTraceEvent>) {
        val filteredCitations = if (isNegativeResponse(finalContent)) emptyList() else citations
        val citationsJson = CitationJson.encode(filteredCitations)
        val msgId = app.container.chatRepository.addMessage(_sessionId.value, MessageRole.ASSISTANT, finalContent, citationsJson)
        val finalTrace = traceEvents + FinalAnswerEvent(finalContent.length)

        updateStreamingMessage {
            it.copy(
                id = msgId,
                content = finalContent,
                citations = filteredCitations,
                isStreaming = false,
                traceEvents = finalTrace,
            )
        }
    }

    private fun removeStreamingMessage() {
        _messages.value = _messages.value.filterNot { it.isStreaming }
    }

    private fun updateStreamingMessage(transform: (ChatMessage) -> ChatMessage) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.isStreaming }
        if (idx >= 0) list[idx] = transform(list[idx])
        _messages.value = list
    }

    /** 最後の回答を捨てて、同じ質問をもう一度送る。 */
    fun regenerate() {
        if (_isGenerating.value) return
        viewModelScope.launch {
            val question = app.container.chatRepository.removeLastExchange(_sessionId.value) ?: return@launch
            // DB の変更通知を待たずに画面からも消し、sendMessage が末尾に積み直す
            val lastUser = _messages.value.indexOfLast { it.role == MessageRole.USER }
            if (lastUser >= 0) _messages.value = _messages.value.subList(0, lastUser)
            sendMessage(question)
        }
    }

    private fun suggestionTexts(): SuggestionTexts = SuggestionTexts(
        lastMonth = app.getString(R.string.chat_suggestion_last_month),
        thisMonth = app.getString(R.string.chat_suggestion_this_month),
        topic = { app.getString(R.string.chat_suggestion_topic, it) },
        fallback = app.resources.getStringArray(R.array.chat_suggestions).toList(),
    )

    private suspend fun loadSuggestions(treeUri: String): List<String> = withContext(Dispatchers.IO) {
        val documentDao = app.container.database.documentDao()
        runCatchingCancellable {
            buildChatSuggestions(
                documentDates = documentDao.getMinimalByTree(treeUri).mapNotNull { it.documentDate },
                recentFileNames = documentDao.getRecentFiles(treeUri, RECENT_FILES_FOR_SUGGESTIONS).map { it.fileName },
                today = LocalDate.now(),
                texts = suggestionTexts(),
            )
        }.getOrElse { suggestionTexts().fallback }
    }

    fun dismissError() {
        _error.value = null
    }

    fun newSession() {
        _error.value = null
        viewModelScope.launch {
            _sessionId.value = app.container.chatRepository.createSession()
            _messages.value = emptyList()
        }
    }

    /** 引用元ファイルの SAF URI。docId を持たない引用や、再インデックスで消えたファイルなら null。 */
    suspend fun citationFileUri(citation: Citation): String? {
        val docId = citation.docId ?: return null
        return app.container.database.documentDao().getById(docId)?.fileUri
    }

    fun cancelGeneration() {
        currentJob?.cancel()
        _isGenerating.value = false
        _statusText.value = null
        // 検索中（本文がまだ空）に止めた場合は空の吹き出しを残さない
        if (_messages.value.lastOrNull { it.isStreaming }?.content.isNullOrEmpty()) {
            removeStreamingMessage()
        } else {
            updateStreamingMessage { it.copy(isStreaming = false) }
        }
    }
}

// 話題の候補を拾うファイル数。日付だけのファイル名が続いても話題が残る程度に多めに取る
private const val RECENT_FILES_FOR_SUGGESTIONS = 20

// 回答冒頭がこれらの「答えられない」系の表現なら、無関係な引用元を表示しない
private val NEGATIVE_KEYWORDS = listOf(
    "お答えできません",
    "分かりません",
    "わかりません",
    "情報が見つかりません",
    "見つかりませんでした",
    "知識ベースにはありません",
    "一般的な知識で回答します",
)

// 回答の途中で「〜については分かりません」と触れただけで引用が消えないよう、冒頭だけを判定する
internal const val NEGATIVE_CHECK_CHARS = 80

internal fun isNegativeResponse(text: String): Boolean {
    val head = text.trimStart().take(NEGATIVE_CHECK_CHARS)
    return NEGATIVE_KEYWORDS.any { head.contains(it) }
}
