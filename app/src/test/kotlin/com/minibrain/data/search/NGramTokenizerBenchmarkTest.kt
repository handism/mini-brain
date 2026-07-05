package com.minibrain.data.search

import org.junit.Test
import kotlin.system.measureNanoTime

class NGramTokenizerBenchmarkTest {

    @Test
    fun benchmark() {
        val text = """
            Here is some Markdown text that is reasonably long.
            We want to test how fast the tokenizer runs.
            これは日本語のテキストです。ちゃんとパースされるべきです。
            There are many words and multiple lines.
            符号、例えば !@#$ もあります。
        """.trimIndent().repeat(1000)

        // Warm up
        for (i in 0 until 10) {
            NGramTokenizer.toBigrams(text)
        }

        var totalTime = 0L
        val iterations = 50
        for (i in 0 until iterations) {
            totalTime += measureNanoTime {
                NGramTokenizer.toBigrams(text)
            }
        }

        println("Average time: ${totalTime / iterations / 1_000_000.0} ms")
    }
}
