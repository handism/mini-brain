package com.minibrain.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.minibrain.R
import com.minibrain.data.repo.IndexingPhase

/** 数字だけを出す場所（Chat のバナー・Settings）で、どの段階の n/総数 かを示す */
@Composable
fun indexingPhaseLabel(phase: IndexingPhase): String = stringResource(
    when (phase) {
        IndexingPhase.SCANNING -> R.string.indexing_phase_scanning
        IndexingPhase.READING -> R.string.indexing_phase_reading
        IndexingPhase.EMBEDDING -> R.string.indexing_phase_embedding
    }
)
