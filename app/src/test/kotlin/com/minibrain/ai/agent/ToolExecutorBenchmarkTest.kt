package com.minibrain.ai.agent

import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import org.junit.Test
import kotlin.system.measureTimeMillis

class ToolExecutorBenchmarkTest {
    @Test
    fun benchmarkNPlus1() {
        val docs = (1L..1000L).map {
            DocumentEntity(id = it, treeUri = "", fileUri = "", fileName = "", relativePath = "", lastModified = 0L, contentHash = "")
        }
        val chunks = (1L..5000L).map {
            ChunkEntity(id = it, docId = (it % 1000) + 1, headingPath = "", text = "text", embedding = ByteArray(0))
        }

        val time = measureTimeMillis {
            val chunksByDoc = HashMap<Long, String>()
            for (chunk in chunks) {
                if (chunk.docId !in chunksByDoc) {
                    chunksByDoc[chunk.docId] = chunk.text
                }
            }
            for (doc in docs) {
                val snippet = chunksByDoc[doc.id] ?: doc.firstParagraph ?: ""
            }
        }
        println("OPTIMIZED_TIME: $time ms")
    }
}
