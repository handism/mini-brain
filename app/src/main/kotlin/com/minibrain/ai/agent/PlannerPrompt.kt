package com.minibrain.ai.agent

object PlannerPrompt {

    fun build(
        question: String,
        plannerHint: String?,
        observations: List<Observation>,
    ): String {
        val obsBlock = formatObservations(observations)
        val hintLine = if (!plannerHint.isNullOrBlank()) "ヒント: $plannerHint\n" else ""
        val canFinalize = observations.isNotEmpty()
        val finalizeInstruction = if (canFinalize) {
            "情報が集まったら finalize。"
        } else {
            "【重要】観測がまだ0件です。必ずツールを1回実行してください。今は finalize 禁止。"
        }
        return """あなたはツール選択エージェントです。質問に答えるためツールで情報を集めます。
ナレッジベースはフォルダ/ファイル名で整理されています。まず glob や list_dir でパスを探し、read_file で本文を読みます。パスで見つからなければ grep や rrf_search でキーワード検索します。

質問: $question
${hintLine}
利用可能ツール:
- glob(pattern): 例 {"tool":"glob","args":{"pattern":"2026/06/*"}}
- list_dir(folder): 例 {"tool":"list_dir","args":{"folder":"日記"}}
- read_file(docId or path): 例 {"tool":"read_file","args":{"docId":12}}
- grep(query, scope?): 例 {"tool":"grep","args":{"query":"AWS","scope":"tech/"}}
- vector_search(query, k?): 例 {"tool":"vector_search","args":{"query":"クラウド設計"}}
- rrf_search(query, k?): 例 {"tool":"rrf_search","args":{"query":"読書記録"}}

${if (observations.isEmpty()) "観測: なし" else "観測:\n$obsBlock"}

$finalizeInstruction
次の1アクションをJSONのみで出力:
{"tool":"<name>","args":{...}} または {"action":"finalize","reason":"..."}
JSON:""".trimIndent()
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
    }

    fun parseDecision(raw: String): PlannerDecision {
        val json = extractJson(raw) ?: return PlannerDecision.ParseError
        if (extractStringValue(json, "action") == "finalize") {
            return PlannerDecision.Finalize(extractStringValue(json, "reason") ?: "")
        }
        val toolName = extractStringValue(json, "tool") ?: return PlannerDecision.ParseError
        val argsJson = extractArgsBlock(json) ?: "{}"
        val tool = parseTool(toolName, argsJson) ?: return PlannerDecision.ParseError
        return PlannerDecision.Call(tool)
    }

    private fun parseTool(name: String, argsJson: String): AgentTool? = when (name) {
        "glob" -> extractStringValue(argsJson, "pattern")?.let { AgentTool.Glob(it) }
        "list_dir" -> extractStringValue(argsJson, "folder")?.let { AgentTool.ListDir(it) }
        "read_file" -> {
            val docId = extractLongValue(argsJson, "docId")
            val path = extractStringValue(argsJson, "path")
            if (docId == null && path == null) null else AgentTool.ReadFile(docId, path)
        }
        "grep" -> extractStringValue(argsJson, "query")?.let { q ->
            AgentTool.Grep(q, extractStringValue(argsJson, "scope"))
        }
        "vector_search" -> extractStringValue(argsJson, "query")?.let { q ->
            AgentTool.VectorSearch(q, extractStringValue(argsJson, "scope"), extractIntValue(argsJson, "k") ?: 10)
        }
        "rrf_search" -> extractStringValue(argsJson, "query")?.let { q ->
            AgentTool.RrfSearch(q, extractIntValue(argsJson, "k") ?: 10)
        }
        else -> null
    }

    // Extracts {"args": {...}} block as a string
    private fun extractArgsBlock(json: String): String? {
        val argsKey = "\"args\""
        val keyIdx = json.indexOf(argsKey)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + argsKey.length)
        if (colonIdx < 0) return null
        val braceIdx = json.indexOf('{', colonIdx)
        if (braceIdx < 0) return null
        var depth = 0
        for (i in braceIdx until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(braceIdx, i + 1)
                }
            }
        }
        return null
    }

    // Extracts the value of a JSON string field "key":"value"
    internal fun extractStringValue(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    // Extracts the value of a JSON number field "key":123
    private fun extractLongValue(json: String, key: String): Long? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractIntValue(json: String, key: String): Int? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJson(raw: String): String? {
        val stripped = raw.replace(Regex("```[a-z]*\\n?"), "").replace("```", "")
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end < 0 || end <= start) return null
        return stripped.substring(start, end + 1)
    }
}
