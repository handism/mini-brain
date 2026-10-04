package com.minibrain.ui.vm

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.ai.agent.AgentResult
import com.minibrain.ai.agent.AgentTraceEvent
import com.minibrain.ai.agent.FinalAnswerEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.CitationJson
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.dataStore
import com.minibrain.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText

    private val savedTreeUri: StateFlow<String?> = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showSearchLog: StateFlow<Boolean> = app.dataStore.data
        .map { prefs -> prefs[PREF_SHOW_SEARCH_LOG] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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
        _errorMessage.value = null
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
            _errorMessage.value = "検索エラー: ${it.message}"
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
            _errorMessage.value = "生成エラー: ${it.message}"
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

    fun newSession() {
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
