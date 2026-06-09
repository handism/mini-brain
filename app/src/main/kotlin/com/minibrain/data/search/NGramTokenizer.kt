package com.minibrain.data.search

object NGramTokenizer {

    fun toBigrams(text: String): String {
        // Replace whitespace and ASCII punctuation with space; lowercase ASCII alnum
        val cleaned = buildString(text.length) {
            for (c in text) {
                when {
                    c.isWhitespace() -> append(' ')
                    c.code < 128 && !c.isLetterOrDigit() -> append(' ')
                    else -> append(c.lowercaseChar())
                }
            }
        }

        val tokens = mutableListOf<String>()
        var i = 0
        while (i < cleaned.length) {
            val c = cleaned[i]
            when {
                c == ' ' -> i++
                c.code < 128 -> {
                    // ASCII alphanumeric run → single token
                    val start = i
                    while (i < cleaned.length && cleaned[i] != ' ' && cleaned[i].code < 128) i++
                    tokens.add(cleaned.substring(start, i))
                }
                else -> {
                    // Non-ASCII run → bigrams + unigrams
                    val start = i
                    while (i < cleaned.length && cleaned[i] != ' ' && cleaned[i].code >= 128) i++
                    val run = cleaned.substring(start, i)

                    // Always add unigrams for non-ASCII to support 1-char search
                    for (j in 0 until run.length) {
                        tokens.add(run.substring(j, j + 1))
                    }

                    // Add bigrams
                    for (j in 0 until run.length - 1) {
                        tokens.add(run.substring(j, j + 2))
                    }
                }
            }
        }
        return tokens.joinToString(" ")
    }

    /** Build an FTS5 MATCH expression from free-form query text. Returns null if no tokens. */
    fun toFtsMatchQuery(text: String): String? {
        val tokens = toBigrams(text).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { "\"$it\"" }
    }
}
