package com.minibrain.ai.agent.tools

object GlobMatcher {
    fun matches(pattern: String, path: String): Boolean {
        val regex = globToRegex(pattern)
        return regex.matches(path)
    }

    fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern[i] == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < pattern.length && pattern[i] == '/') i++
                }
                pattern[i] == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                pattern[i] == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                else -> {
                    sb.append(Regex.escape(pattern[i].toString()))
                    i++
                }
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}
