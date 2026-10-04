package com.minibrain.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.data.db.entities.ChatSessionSummary
import com.minibrain.ui.vm.ChatHistoryViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    onBack: () -> Unit,
    onSelectSession: (Long) -> Unit,
    vm: ChatHistoryViewModel = viewModel(),
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.history_deleted)
    val undoLabel = stringResource(R.string.undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        onClick = { onSelectSession(session.id) },
                        onDelete = {
                            vm.deleteSession(session.id)
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                val result = snackbarHostState.showSnackbar(
                                    message = deletedMessage,
                                    actionLabel = undoLabel,
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSessionSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun formatSessionTime(millis: Long): String =
    when (val label = sessionTimeLabel(millis, LocalDate.now(), ZoneId.systemDefault())) {
        is SessionTimeLabel.Today -> stringResource(R.string.history_today, label.time)
        is SessionTimeLabel.Yesterday -> stringResource(R.string.history_yesterday, label.time)
        is SessionTimeLabel.Date -> label.text
    }

internal sealed class SessionTimeLabel {
    data class Today(val time: String) : SessionTimeLabel()
    data class Yesterday(val time: String) : SessionTimeLabel()
    data class Date(val text: String) : SessionTimeLabel()
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm")
private val SAME_YEAR_FORMAT = DateTimeFormatter.ofPattern("M/d H:mm")
private val FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d")

/** 今日・昨日は時刻だけ、今年は月日、それより前は年月日で表す。 */
internal fun sessionTimeLabel(millis: Long, today: LocalDate, zone: ZoneId): SessionTimeLabel {
    val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> SessionTimeLabel.Today(dateTime.format(TIME_FORMAT))
        date == today.minusDays(1) -> SessionTimeLabel.Yesterday(dateTime.format(TIME_FORMAT))
        date.year == today.year -> SessionTimeLabel.Date(dateTime.format(SAME_YEAR_FORMAT))
        else -> SessionTimeLabel.Date(dateTime.format(FULL_FORMAT))
    }
}
