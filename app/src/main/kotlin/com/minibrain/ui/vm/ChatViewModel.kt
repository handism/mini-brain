package com.minibrain.ui.vm

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.ai.agent.AgentTraceEvent
import com.minibrain.ai.agent.FinalAnswerEvent
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import com.minibrain.data.db.entities.MessageRole
import com.minibrain.dataStore
import kotlinx.coroutines.Job
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
    private var currentJob: Job? = null

    init {
        viewModelScope.launch {
            _sessionId.value = if (navSessionId > 0L) navSessionId
                               else app.chatRepository.getOrCreateSession()
        }

        viewModelScope.launch {
            _sessionId.collectLatest { id ->
                if (id == -1L) return@collectLatest
                app.chatRepository.observeMessages(id).collect { entities ->
                    if (!_isGenerating.value) {
                        val existingTrace = _messages.value.associate { it.id to it.traceEvents }
                        _messages.value = entities.map { entity ->
                            ChatMessage(
                                id = entity.id,
                                role = entity.role,
                                content = entity.content,
                                citations = parseCitations(entity.citationsJson),
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
        currentJob = viewModelScope.launch {
            _isGenerating.value = true
            _errorMessage.value = null
            _statusText.value = null

            // ユーザーメッセージを追加（最初の送信時はセッションタイトルを質問で更新）
            if (_messages.value.isEmpty()) {
                val title = question.take(40).let { if (question.length > 40) "$it…" else it }
                app.chatRepository.updateSessionTitle(_sessionId.value, title)
            }
            val userMsg = ChatMessage(role = MessageRole.USER, content = question)
            _messages.value = _messages.value + userMsg
            app.chatRepository.addMessage(_sessionId.value, MessageRole.USER, question)

            // ストリーミングプレースホルダーを先行追加（検索中も CircularProgressIndicator 表示）
            val streamingMsg = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true,
            )
            _messages.value = _messages.value + streamingMsg

            val treeUri = savedTreeUri.value ?: ""

            val history = app.chatRepository.getRecentHistory(_sessionId.value).map { msg ->
                Pair(msg.role.name.lowercase(), msg.content)
            }

            // エージェントループ（計画 → 多段ツール実行 → 回答）
            val agentResult = runCatching {
                app.agentPipeline.run(question, treeUri, history) { status ->
                    _statusText.value = status.ifBlank { null }
                }
            }.getOrElse {
                _errorMessage.value = "検索エラー: ${it.message}"
                _isGenerating.value = false
                removeStreamingMessage()
                return@launch
            }

            val citations = agentResult.citations
            _statusText.value = null

            // 引用元をストリーミングメッセージに反映
            updateStreamingMessage { it.copy(citations = citations) }

            val sb = StringBuilder()
            runCatching {
                agentResult.answerFlow.collect { token ->
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

            // ストリーミング完了
            val finalContent = sb.toString()
            val filteredCitations = if (isNegativeResponse(finalContent)) emptyList() else citations
            val citationsJson = serializeCitations(filteredCitations)
            val msgId = app.chatRepository.addMessage(_sessionId.value, MessageRole.ASSISTANT, finalContent, citationsJson)
            val finalTrace = agentResult.traceEvents + FinalAnswerEvent(finalContent.length)

            val finalList = _messages.value.toMutableList()
            val idx = finalList.indexOfLast { it.isStreaming }
            if (idx >= 0) {
                finalList[idx] = finalList[idx].copy(
                    id = msgId,
                    content = finalContent,
                    citations = filteredCitations,
                    isStreaming = false,
                    traceEvents = finalTrace,
                )
                _messages.value = finalList
            }

            _isGenerating.value = false
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
            _sessionId.value = app.chatRepository.createSession()
            _messages.value = emptyList()
        }
    }

    fun cancelGeneration() {
        currentJob?.cancel()
        _isGenerating.value = false
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.isStreaming }
        if (idx >= 0) list[idx] = list[idx].copy(isStreaming = false)
        _messages.value = list
    }

    private fun isNegativeResponse(text: String): Boolean {
        val negativeKeywords = listOf(
            "お答えできません",
            "分かりません",
            "わかりません",
            "情報が見つかりません",
            "見つかりませんでした",
            "知識ベースにはありません",
            "一般的な知識で回答します"
        )
        return negativeKeywords.any { text.contains(it) }
    }

    private fun parseCitations(json: String): List<Citation> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Citation(
                headingPath = obj.getString("headingPath"),
                snippet = obj.getString("snippet"),
                score = obj.optDouble("score", 0.0).toFloat(),
                docId = if (obj.has("docId")) obj.getLong("docId") else null,
                relativePath = obj.optString("relativePath").ifBlank { null },
                source = runCatching { SourceType.valueOf(obj.optString("source")) }.getOrElse { SourceType.UNKNOWN },
            )
        }
    }.getOrElse { emptyList() }

    private fun serializeCitations(citations: List<Citation>): String = runCatching {
        JSONArray().also { arr ->
            citations.forEach { c ->
                val obj = JSONObject()
                    .put("headingPath", c.headingPath)
                    .put("snippet", c.snippet)
                    .put("score", c.score)
                    .put("source", c.source.name)
                c.docId?.let { obj.put("docId", it) }
                c.relativePath?.let { obj.put("relativePath", it) }
                arr.put(obj)
            }
        }.toString()
    }.getOrElse { "[]" }
}
