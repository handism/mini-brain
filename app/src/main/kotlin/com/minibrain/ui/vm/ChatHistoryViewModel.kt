package com.minibrain.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.data.db.entities.ChatSessionSummary
import com.minibrain.data.repo.DeletedSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val sessions: StateFlow<List<ChatSessionSummary>> = app.container.chatRepository
        .observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Snackbar の「元に戻す」で戻せるのは直前に削除した 1 件だけ
    private var lastDeleted: DeletedSession? = null

    fun deleteSession(id: Long) {
        viewModelScope.launch { lastDeleted = app.container.chatRepository.deleteSession(id) }
    }

    fun undoDelete() {
        val deleted = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch { app.container.chatRepository.restoreSession(deleted) }
    }
}
