package com.minibrain.ai.agent.tools

import androidx.sqlite.db.SimpleSQLiteQuery
import com.minibrain.ai.agent.AgentTool
import com.minibrain.ai.agent.ToolCall
import com.minibrain.ai.agent.ToolResult
import com.minibrain.ai.embed.EmbedType
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.CosineSimilarity
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.ai.rag.SourceType
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.DocumentEntity
import com.minibrain.data.search.NGramTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber

private const val TAG = "ToolExecutor"
private const val MAX_GLOB_RESULTS = 30
private const val MAX_GREP_RESULTS = 20
private const val GREP_SNIPPET_CHARS = 200
private const val READ_FILE_MAX_CHARS = 8000
private const val READ_SECTION_MAX_CHARS = 1000
// ファイル内容がこの文字数を超えたら LLM で要約してから citation に投入 (≒ 1000 tokens)
private const val SUMMARIZE_THRESHOLD_CHARS = 3000

class ToolExecutor(
    private val documentDao: DocumentDao,
    private val chunkDao: ChunkDao,
    private val embedderService: EmbedderService,
    private val ragPipeline: RagPipeline,
    private val treeUri: String,
    private val llmService: LlmService,
    private val cache: SearchRequestCache,
) {
    private val queryVecCache = mutableMapOf<String, FloatArray>()

    private suspend fun allDocs(): List<DocumentEntity> = cache.documents()

    suspend fun execute(call: ToolCall): ToolResult = when (val tool = call.tool) {
        is AgentTool.Glob -> executeGlob(call, tool)
        is AgentTool.ListDir -> executeListDir(call, tool)
        is AgentTool.ReadFile -> executeReadFile(call, tool)
        is AgentTool.Grep -> executeGrep(call, tool)
        is AgentTool.VectorSearch -> executeVectorSearch(call, tool)
        is AgentTool.RrfSearch -> executeRrfSearch(call, tool)
        is AgentTool.TimelineSearch -> executeTimelineSearch(call, tool)
    }

    private suspend fun executeGlob(call: ToolCall, tool: AgentTool.Glob): ToolResult {
        val docs = allDocs()
        val regex = GlobMatcher.globToRegex(tool.pattern)
        val matched = docs.filter { regex.matches(it.relativePath) }.take(MAX_GLOB_RESULTS)
        val lines = matched.joinToString("\n") { doc ->
            val hint = doc.firstParagraph?.take(40)
                ?: doc.headings?.let { parseFirstHeadings(it, 1) } ?: ""
            "- [d=${doc.id}] ${doc.relativePath}${if (hint.isNotBlank()) ": $hint" else ""}"
        }
        val text = if (matched.isEmpty()) {
            "MATCHES: 0 files"
        } else {
            "MATCHES: ${matched.size} files\n$lines${if (matched.size >= MAX_GLOB_RESULTS) "\n... (and more)" else ""}"
        }
        val citations = matched.map { doc ->
            Citation(
                headingPath = doc.relativePath,
                snippet = doc.firstParagraph ?: "",
                score = 0f,
                docId = doc.id,
                relativePath = doc.relativePath,
                source = SourceType.GLOB,
            )
        }
        return ToolResult(call, text, citations)
    }

    private suspend fun executeListDir(call: ToolCall, tool: AgentTool.ListDir): ToolResult {
        val docs = allDocs()
        val prefix = tool.folder.trimEnd('/')
        val inFolder = docs.filter { it.relativePath.startsWith("$prefix/") }
        val subFolders = inFolder.mapNotNull { doc ->
            val rel = doc.relativePath.removePrefix("$prefix/")
            val slash = rel.indexOf('/')
            if (slash >= 0) rel.substring(0, slash) else null
        }.toSortedSet()
        val directFiles = inFolder.filter { doc ->
            val rel = doc.relativePath.removePrefix("$prefix/")
            '/' !in rel
        }
        val sb = StringBuilder("DIR: ${tool.folder}\n")
        if (subFolders.isNotEmpty()) sb.append("folders: [${subFolders.joinToString(", ")}]\n")
        if (directFiles.isNotEmpty()) {
            sb.append("files:\n")
            directFiles.forEach { doc ->
                val hint = doc.firstParagraph?.take(40) ?: ""
                sb.append("- [d=${doc.id}] ${doc.fileName}${if (hint.isNotBlank()) ": $hint" else ""}\n")
            }
        }
        if (subFolders.isEmpty() && directFiles.isEmpty()) sb.append("(empty)")
        return ToolResult(call, sb.toString().trimEnd(), emptyList())
    }

    private suspend fun executeReadFile(call: ToolCall, tool: AgentTool.ReadFile): ToolResult {
        val doc = withContext(Dispatchers.IO) {
            when {
                tool.docId != null -> documentDao.getById(tool.docId)
                tool.path != null -> {
                    val keyword = tool.path.substringAfterLast("/").removeSuffix(".md")
                    documentDao.searchByPath(treeUri, keyword).firstOrNull()
                }
                else -> null
            }
        } ?: return ToolResult(call, "FILE NOT FOUND", emptyList())

        val chunks = withContext(Dispatchers.IO) {
            chunkDao.getByDoc(doc.id).sortedBy { it.headingPath }
        }

        val headings = doc.headings?.let { parseFirstHeadings(it, 5) } ?: ""
        val tags = doc.tags?.let { parseJsonArray(it) }?.joinToString(", ") ?: ""

        val sb = StringBuilder("FILE: ${doc.relativePath}\n")
        if (headings.isNotBlank()) sb.append("headings: [$headings]\n")
        if (tags.isNotBlank()) sb.append("tags: [$tags]\n")
        sb.append("---\n")

        var totalChars = sb.length
        for (chunk in chunks) {
            val section = "## ${chunk.headingPath}\n${chunk.text}\n\n"
            if (totalChars + section.length > READ_FILE_MAX_CHARS) {
                val truncated = "## ${chunk.headingPath}\n${chunk.text.take(READ_SECTION_MAX_CHARS)}...[truncated]\n\n"
                sb.append(truncated)
                totalChars += truncated.length
                if (totalChars > READ_FILE_MAX_CHARS) break
            } else {
                sb.append(section)
                totalChars += section.length
            }
        }

        val fullText = sb.toString().trimEnd()
        val citations = if (fullText.length > SUMMARIZE_THRESHOLD_CHARS) {
            // 巨大ファイルは要約してから単一の citation として投入
            val summary = runCatching { llmService.summarize(fullText) }
                .onFailure { Timber.tag(TAG).w(it, "summarize failed for ${doc.relativePath}, truncating") }
                .getOrElse { fullText.take(SUMMARIZE_THRESHOLD_CHARS) }
            listOf(Citation(
                headingPath = doc.relativePath,
                snippet = summary,
                score = 1f,
                docId = doc.id,
                relativePath = doc.relativePath,
                source = SourceType.READ_FILE,
            ))
        } else {
            chunks.map { chunk ->
                Citation(
                    headingPath = chunk.headingPath,
                    snippet = chunk.text,
                    score = 1f,
                    docId = doc.id,
                    relativePath = doc.relativePath,
                    source = SourceType.READ_FILE,
                )
            }
        }
        return ToolResult(call, fullText, citations)
    }

    private suspend fun executeGrep(call: ToolCall, tool: AgentTool.Grep): ToolResult {
        val matchQuery = NGramTokenizer.toFtsMatchQuery(tool.query)
            ?: return ToolResult(call, "GREP: query too short", emptyList())

        val rawChunks = withContext(Dispatchers.IO) {
            runCatching {
                chunkDao.bm25Search(matchQuery, 50)
            }.onFailure { Timber.tag(TAG).w(it, "bm25Search failed for query: $matchQuery") }
             .getOrElse { emptyList() }
        }

        val docsMapForGrep = cache.documents().associateBy { it.id }

        val filtered = if (tool.scope != null) {
            rawChunks.filter { chunk ->
                val doc = docsMapForGrep[chunk.docId]
                doc?.relativePath?.startsWith(tool.scope) == true
            }
        } else rawChunks

        val hits = filtered.take(MAX_GREP_RESULTS)
        val citations = hits.map { chunk ->
            val doc = docsMapForGrep[chunk.docId]
            Citation(
                headingPath = chunk.headingPath,
                snippet = chunk.text,
                score = 0.5f,
                docId = chunk.docId,
                relativePath = doc?.relativePath,
                source = SourceType.GREP,
            )
        }

        val lines = hits.joinToString("\n") { chunk ->
            val doc = docsMapForGrep[chunk.docId]
            val path = doc?.relativePath ?: "d=${chunk.docId}"
            "- [d=${chunk.docId} p=$path] ${chunk.headingPath}: ${chunk.text.take(GREP_SNIPPET_CHARS)}"
        }
        val text = if (hits.isEmpty()) {
            "GREP \"${tool.query}\": 0 hits"
        } else {
            "GREP \"${tool.query}\": ${filtered.size} hits (showing ${hits.size})\n$lines"
        }
        return ToolResult(call, text, citations)
    }

    private suspend fun executeVectorSearch(call: ToolCall, tool: AgentTool.VectorSearch): ToolResult {
        val vec = queryVecCache.getOrPut(tool.query) { embedderService.embed(tool.query, EmbedType.QUERY) }

        val topChunks = if (tool.scope == null) {
            cache.cosineTopK(vec, tool.k)
        } else {
            val (chunks, vectors) = cache.chunkVectors()
            val docsMap = cache.documents().associateBy { it.id }
            val validDocIds = docsMap.values
                .mapNotNull { if (it.relativePath.startsWith(tool.scope)) it.id else null }
                .toSet()
            val filteredCandidates = ArrayList<Pair<FloatArray, com.minibrain.data.db.entities.ChunkEntity>>()
            for (i in chunks.indices) {
                val chunk = chunks[i]
                if (chunk.docId in validDocIds) {
                    filteredCandidates.add(Pair(vectors[i], chunk))
                }
            }
            @Suppress("UNCHECKED_CAST")
            CosineSimilarity.topK(
                vec,
                filteredCandidates as List<Pair<FloatArray, Any>>,
                tool.k
            ).map { (score, meta) -> Pair(score, meta as com.minibrain.data.db.entities.ChunkEntity) }
        }

        val docsMapForVector = cache.documents().associateBy { it.id }
        val citations = topChunks.map { (score, chunk) ->
            val doc = docsMapForVector[chunk.docId]
            Citation(
                headingPath = chunk.headingPath,
                snippet = chunk.text,
                score = score,
                docId = chunk.docId,
                relativePath = doc?.relativePath,
                source = SourceType.VECTOR,
            )
        }

        val lines = citations.joinToString("\n") { c ->
            "- [d=${c.docId} p=${c.relativePath}] ${c.headingPath} (score=%.2f): ${c.snippet.take(GREP_SNIPPET_CHARS)}".format(c.score)
        }
        val text = if (citations.isEmpty()) {
            "VECTOR \"${tool.query}\": 0 results"
        } else {
            "VECTOR \"${tool.query}\": ${citations.size} results\n$lines"
        }
        return ToolResult(call, text, citations)
    }

    private suspend fun executeRrfSearch(call: ToolCall, tool: AgentTool.RrfSearch): ToolResult {
        val citations = ragPipeline.retrieveTopChunks(tool.query, treeUri, tool.k, cache = cache)
        val lines = citations.joinToString("\n") { c ->
            "- [d=${c.docId} p=${c.relativePath}] ${c.headingPath} (score=%.4f): ${c.snippet.take(GREP_SNIPPET_CHARS)}".format(c.score)
        }
        val text = if (citations.isEmpty()) {
            "RRF \"${tool.query}\": 0 results"
        } else {
            "RRF \"${tool.query}\": ${citations.size} results\n$lines"
        }
        return ToolResult(call, text, citations)
    }

    private suspend fun executeTimelineSearch(call: ToolCall, tool: AgentTool.TimelineSearch): ToolResult {
        val docs = withContext(Dispatchers.IO) {
            documentDao.getByDateRange(treeUri, tool.startDate, tool.endDate)
        }.take(tool.limit)

        if (docs.isEmpty()) {
            return ToolResult(
                call,
                "TIMELINE \"${tool.startDate}\" ~ \"${tool.endDate}\": 0 documents",
                emptyList(),
            )
        }

        val citations = mutableListOf<Citation>()
        val lines = mutableListOf<String>()
        val cachedChunksList = cache.chunkVectors().first
        val chunksByDoc = HashMap<Long, String>()
        for (chunk in cachedChunksList) {
            chunksByDoc.putIfAbsent(chunk.docId, chunk.text)
        }
        for (doc in docs) {
            val snippet = chunksByDoc[doc.id] ?: doc.firstParagraph ?: ""
            citations.add(Citation(
                headingPath = doc.relativePath,
                snippet = snippet.take(GREP_SNIPPET_CHARS),
                score = 0.7f,
                docId = doc.id,
                relativePath = doc.relativePath,
                source = SourceType.GREP,
            ))
            val dateTag = doc.documentDate?.let { " ($it)" } ?: ""
            lines.add("- [d=${doc.id}] ${doc.relativePath}$dateTag: ${snippet.take(80)}")
        }
        val text = "TIMELINE \"${tool.startDate}\" ~ \"${tool.endDate}\": ${docs.size} documents\n${lines.joinToString("\n")}"
        return ToolResult(call, text, citations)
    }

    private fun parseFirstHeadings(json: String, count: Int): String = runCatching {
        val arr = JSONArray(json)
        (0 until minOf(arr.length(), count)).joinToString(", ") { i -> arr.getString(i) }
    }.onFailure { Timber.tag(TAG).w(it, "parseFirstHeadings failed: $json") }
     .getOrElse { "" }

    private fun parseJsonArray(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i -> arr.getString(i) }
    }.onFailure { Timber.tag(TAG).w(it, "parseJsonArray failed: $json") }
     .getOrElse { emptyList() }
}
