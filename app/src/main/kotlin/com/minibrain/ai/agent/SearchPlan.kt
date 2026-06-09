package com.minibrain.ai.agent

data class SearchPlan(
    val intent: String,
    val reasoning: String,
    val targetFolders: List<String>,
    val targetFiles: List<String>,
    val searchKeywords: List<String>,
)
