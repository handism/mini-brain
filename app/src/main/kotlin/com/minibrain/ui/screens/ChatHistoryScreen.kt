package com.minibrain.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    var query by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.history_deleted)
    val undoLabel = stringResource(R.string.undo)

    val onDelete: (Long) -> Unit = { id ->
        vm.deleteSession(id)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
        }
    }

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
            CenteredMessage(stringResource(R.string.history_empty), Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            HistorySearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val grouped = filterSessions(sessions, query).groupBy { sessionGroup(it.updatedAt, today, zone) }
            if (grouped.isEmpty()) {
                CenteredMessage(stringResource(R.string.history_search_empty))
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // observeSummaries は新しい順なので、グループもその順に並ぶ
                grouped.forEach { (group, items) ->
                    item(key = "header-$group") { SessionGroupHeader(group) }
                    items(items, key = { it.id }) { session ->
                        SwipeToDeleteSessionItem(
                            session = session,
                            onClick = { onSelectSession(session.id) },
                            onDelete = { onDelete(session.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistorySearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.history_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cancel))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun SessionGroupHeader(group: SessionGroup) {
    Text(
        stringResource(
            when (group) {
                SessionGroup.TODAY -> R.string.history_group_today
                SessionGroup.YESTERDAY -> R.string.history_group_yesterday
                SessionGroup.LAST_7_DAYS -> R.string.history_group_week
                SessionGroup.OLDER -> R.string.history_group_older
            }
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteSessionItem(
    session: ChatSessionSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val deleteLabel = stringResource(R.string.delete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        ListItem(
            headlineContent = {
                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = { Text(formatSessionTime(session.updatedAt)) },
            modifier = Modifier
                .clickable(onClick = onClick)
                // スワイプできない人向けに、TalkBack の操作メニューから削除できるようにする
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                },
        )
    }
}

/** 見出しで今日・昨日が分かるので、その 2 つは時刻だけ出す。 */
@Composable
private fun formatSessionTime(millis: Long): String =
    when (val label = sessionTimeLabel(millis, LocalDate.now(), ZoneId.systemDefault())) {
        is SessionTimeLabel.Today -> label.time
        is SessionTimeLabel.Yesterday -> label.time
        is SessionTimeLabel.Date -> label.text
    }

internal fun filterSessions(sessions: List<ChatSessionSummary>, query: String): List<ChatSessionSummary> {
    val q = query.trim()
    return if (q.isEmpty()) sessions else sessions.filter { it.title.contains(q, ignoreCase = true) }
}

internal enum class SessionGroup { TODAY, YESTERDAY, LAST_7_DAYS, OLDER }

internal fun sessionGroup(millis: Long, today: LocalDate, zone: ZoneId): SessionGroup {
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return when {
        !date.isBefore(today) -> SessionGroup.TODAY
        date == today.minusDays(1) -> SessionGroup.YESTERDAY
        date.isAfter(today.minusDays(7)) -> SessionGroup.LAST_7_DAYS
        else -> SessionGroup.OLDER
    }
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
