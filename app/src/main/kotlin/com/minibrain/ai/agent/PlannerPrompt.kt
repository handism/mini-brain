package com.minibrain.ai.agent

object PlannerPrompt {

    // キー: 値 形式を解析する regex (大文字小文字どちらも受け付ける)
    private val KEY_VALUE_RE = Regex("""^([A-Za-z_]+):\s*(.+)$""", RegexOption.MULTILINE)

    fun build(
        question: String,
        plannerHint: String?,
        observations: List<Observation>,
    ): String {
        val obsBlock = formatObservations(observations)
        val hintLine = if (!plannerHint.isNullOrBlank()) "ヒント: $plannerHint\n" else ""
        val canFinalize = observations.isNotEmpty()
        val finalizeInstruction = if (canFinalize) {
            "情報が集まったら finalize を選択してください。"
        } else {
            "【重要】観測がまだ0件です。必ずツールを1回実行してください。今は finalize 禁止。"
        }
        return """あなたはツール選択エージェントです。質問に答えるためツールで情報を集めます。
ナレッジベースはフォルダ/ファイル名で整理されています。まず glob や list_dir でパスを探し、read_file で本文を読みます。パスで見つからなければ grep や rrf_search でキーワード検索します。

質問: $question
${hintLine}
利用可能ツール（以下の形式で1つを選んで出力）:

TOOL: glob
PATTERN: <パターン例: 2026/06/*>

TOOL: list_dir
FOLDER: <フォルダ名>

TOOL: read_file
DOC_ID: <数値>
（またはPATH: <パス>）

TOOL: grep
QUERY: <キーワード>
SCOPE: <フォルダ>（省略可）

TOOL: vector_search
QUERY: <クエリ>
K: <件数>（省略可）

TOOL: rrf_search
QUERY: <クエリ>

TOOL: timeline_search
START: <yyyy-MM-dd>
END: <yyyy-MM-dd>
LIMIT: <件数>（省略可）

情報収集完了の場合:
ACTION: finalize
REASON: <理由>

${if (observations.isEmpty()) "観測: なし" else "観測:\n$obsBlock"}

$finalizeInstruction
次の1アクションをキー: 値 形式のみで出力してください（JSONは使わないこと）:""".trimIndent()
    }

    private fun formatObservations(observations: List<Observation>): String {
        if (observations.isEmpty()) return ""
        val sb = StringBuilder()
        observations.forEach { obs ->
            val toolDesc = toolCallDescription(obs.call.tool)
            if (obs.full) {
                val truncated = obs.text.take(1500)
                val suffix = if (obs.text.length > 1500) "\n...[truncated]" else ""
                sb.append("OBS#${obs.call.iteration} $toolDesc:\n$truncated$suffix\n\n")
            } else {
                sb.append("OBS#${obs.call.iteration} $toolDesc → ${obs.text.lines().firstOrNull() ?: ""}\n")
            }
        }
        val result = sb.toString()
        return if (result.length > 5000) result.takeLast(5000) else result
    }

    private fun toolCallDescription(tool: AgentTool): String = when (tool) {
        is AgentTool.Glob -> "glob(\"${tool.pattern}\")"
        is AgentTool.ListDir -> "list_dir(\"${tool.folder}\")"
        is AgentTool.ReadFile -> when {
            tool.docId != null -> "read_file(docId=${tool.docId})"
            tool.path != null -> "read_file(\"${tool.path}\")"
            else -> "read_file(?)"
        }
        is AgentTool.Grep -> "grep(\"${tool.query}\"${if (tool.scope != null) ", scope=\"${tool.scope}\"" else ""})"
        is AgentTool.VectorSearch -> "vector_search(\"${tool.query}\", k=${tool.k})"
        is AgentTool.RrfSearch -> "rrf_search(\"${tool.query}\", k=${tool.k})"
        is AgentTool.TimelineSearch -> "timeline_search(\"${tool.startDate}\"~\"${tool.endDate}\", limit=${tool.limit})"
    }

    fun parseDecision(raw: String): PlannerDecision {
        val pairs = KEY_VALUE_RE.findAll(raw).associate {
            it.groupValues[1].uppercase().trim() to it.groupValues[2].trim()
        }

        if (pairs["ACTION"]?.lowercase() == "finalize") {
            return PlannerDecision.Finalize(pairs["REASON"] ?: "")
        }

        val toolName = pairs["TOOL"] ?: return PlannerDecision.ParseError
        return buildTool(toolName.lowercase().replace("-", "_"), pairs)
            ?.let { PlannerDecision.Call(it) }
            ?: PlannerDecision.ParseError
    }

    private fun buildTool(name: String, pairs: Map<String, String>): AgentTool? = when (name) {
        "glob" -> pairs["PATTERN"]?.let { AgentTool.Glob(it) }
        "list_dir" -> pairs["FOLDER"]?.let { AgentTool.ListDir(it) }
        "read_file" -> {
            val docId = pairs["DOC_ID"]?.toLongOrNull()
            val path = pairs["PATH"]
            if (docId == null && path == null) null else AgentTool.ReadFile(docId, path)
        }
        "grep" -> pairs["QUERY"]?.let { q ->
            AgentTool.Grep(q, pairs["SCOPE"])
        }
        "vector_search" -> pairs["QUERY"]?.let { q ->
            AgentTool.VectorSearch(q, pairs["SCOPE"], pairs["K"]?.toIntOrNull() ?: 10)
        }
        "rrf_search" -> pairs["QUERY"]?.let { q ->
            AgentTool.RrfSearch(q, pairs["K"]?.toIntOrNull() ?: 10)
        }
        "timeline_search" -> {
            val start = pairs["START"]
            val end = pairs["END"]
            if (start != null && end != null) {
                AgentTool.TimelineSearch(start, end, pairs["LIMIT"]?.toIntOrNull() ?: 20)
            } else null
        }
        else -> null
    }
}
