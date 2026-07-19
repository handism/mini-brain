package com.minibrain.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val sessions: StateFlow<List<ChatSessionEntity>> = app.container.chatRepository
        .observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteSession(id: Long) {
        viewModelScope.launch { app.container.chatRepository.deleteSession(id) }
    }
}
