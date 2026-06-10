package com.minibrain.ai.agent

import com.minibrain.ai.agent.tools.GlobMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobMatcherTest {

    @Test
    fun `single star matches within folder only`() {
        assertTrue(GlobMatcher.matches("2026/06/*", "2026/06/01.md"))
        assertTrue(GlobMatcher.matches("2026/06/*", "2026/06/日記.md"))
        assertFalse(GlobMatcher.matches("2026/06/*", "2026/06/sub/x.md"))
        assertFalse(GlobMatcher.matches("2026/06/*", "2026/07/01.md"))
    }

    @Test
    fun `double star matches recursively`() {
        assertTrue(GlobMatcher.matches("proj/**/*.md", "proj/sub/file.md"))
        assertTrue(GlobMatcher.matches("proj/**/*.md", "proj/a/b/c.md"))
        assertTrue(GlobMatcher.matches("proj/**", "proj/README.md"))
        assertTrue(GlobMatcher.matches("proj/**", "proj/sub/deep.md"))
    }

    @Test
    fun `dot in pattern is escaped`() {
        assertTrue(GlobMatcher.matches("2026/06/01.md", "2026/06/01.md"))
        assertFalse(GlobMatcher.matches("2026/06/01.md", "2026/06/01Xmd"))
    }

    @Test
    fun `japanese path matches`() {
        assertTrue(GlobMatcher.matches("日記/*", "日記/2026-06.md"))
        assertFalse(GlobMatcher.matches("日記/*", "他/2026-06.md"))
    }

    @Test
    fun `question mark matches single non-slash character`() {
        assertTrue(GlobMatcher.matches("2026/0?.md", "2026/06.md"))
        assertFalse(GlobMatcher.matches("2026/0?.md", "2026/06/x.md"))
    }

    @Test
    fun `no wildcard matches exact path`() {
        assertTrue(GlobMatcher.matches("proj/README.md", "proj/README.md"))
        assertFalse(GlobMatcher.matches("proj/README.md", "proj/readme.md"))
    }
}
