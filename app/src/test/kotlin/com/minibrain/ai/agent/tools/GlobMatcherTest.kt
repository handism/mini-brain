package com.minibrain.ai.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobMatcherTest {

    @Test
    fun testExactMatch() {
        assertTrue(GlobMatcher.matches("file.txt", "file.txt"))
        assertFalse(GlobMatcher.matches("file.txt", "file2.txt"))
        assertTrue(GlobMatcher.matches("dir/file.txt", "dir/file.txt"))
        assertFalse(GlobMatcher.matches("dir/file.txt", "dir2/file.txt"))
        assertTrue(GlobMatcher.matches("proj/README.md", "proj/README.md"))
        assertFalse(GlobMatcher.matches("proj/README.md", "proj/readme.md"))
    }

    @Test
    fun testSingleAsterisk() {
        assertTrue(GlobMatcher.matches("*.txt", "file.txt"))
        assertFalse(GlobMatcher.matches("*.txt", "file.md"))

        // Single asterisk shouldn't match across directories
        assertFalse(GlobMatcher.matches("*.txt", "dir/file.txt"))
        assertTrue(GlobMatcher.matches("dir/*.txt", "dir/file.txt"))
        assertFalse(GlobMatcher.matches("dir/*.txt", "dir/subdir/file.txt"))

        assertTrue(GlobMatcher.matches("2026/06/*", "2026/06/01.md"))
        assertTrue(GlobMatcher.matches("2026/06/*", "2026/06/日記.md"))
        assertFalse(GlobMatcher.matches("2026/06/*", "2026/06/sub/x.md"))
        assertFalse(GlobMatcher.matches("2026/06/*", "2026/07/01.md"))
    }

    @Test
    fun testDoubleAsterisk() {
        assertTrue(GlobMatcher.matches("**/*.txt", "file.txt"))
        assertTrue(GlobMatcher.matches("**/*.txt", "dir/file.txt"))
        assertTrue(GlobMatcher.matches("**/*.txt", "dir/subdir/file.txt"))

        assertTrue(GlobMatcher.matches("src/**/*.kt", "src/Main.kt"))
        assertTrue(GlobMatcher.matches("src/**/*.kt", "src/com/example/Main.kt"))
        assertFalse(GlobMatcher.matches("src/**/*.kt", "test/Main.kt"))
        assertFalse(GlobMatcher.matches("src/**/*.kt", "src/com/example/Main.java"))

        assertTrue(GlobMatcher.matches("proj/**/*.md", "proj/sub/file.md"))
        assertTrue(GlobMatcher.matches("proj/**/*.md", "proj/a/b/c.md"))
        assertTrue(GlobMatcher.matches("proj/**", "proj/README.md"))
        assertTrue(GlobMatcher.matches("proj/**", "proj/sub/deep.md"))
    }

    @Test
    fun testQuestionMark() {
        assertTrue(GlobMatcher.matches("file?.txt", "file1.txt"))
        assertTrue(GlobMatcher.matches("file?.txt", "fileA.txt"))
        assertFalse(GlobMatcher.matches("file?.txt", "file12.txt"))
        assertFalse(GlobMatcher.matches("file?.txt", "file.txt"))

        assertTrue(GlobMatcher.matches("2026/0?.md", "2026/06.md"))
        assertFalse(GlobMatcher.matches("2026/0?.md", "2026/06/x.md"))

        // Question mark shouldn't match directory separator
        assertFalse(GlobMatcher.matches("dir?file.txt", "dir/file.txt"))
    }

    @Test
    fun testJapanesePath() {
        assertTrue(GlobMatcher.matches("日記/*", "日記/2026-06.md"))
        assertFalse(GlobMatcher.matches("日記/*", "他/2026-06.md"))
    }

    @Test
    fun testSpecialRegexCharactersEscaped() {
        // Characters like ., +, (, ), [ should be treated literally
        assertTrue(GlobMatcher.matches("file.txt", "file.txt"))
        assertFalse(GlobMatcher.matches("file.txt", "fileXtxt"))
        assertTrue(GlobMatcher.matches("2026/06/01.md", "2026/06/01.md"))
        assertFalse(GlobMatcher.matches("2026/06/01.md", "2026/06/01Xmd"))

        assertTrue(GlobMatcher.matches("file+name.txt", "file+name.txt"))
        assertFalse(GlobMatcher.matches("file+name.txt", "fileename.txt"))

        assertTrue(GlobMatcher.matches("file(name).txt", "file(name).txt"))
        assertFalse(GlobMatcher.matches("file(name).txt", "filename.txt"))

        assertTrue(GlobMatcher.matches("file[name].txt", "file[name].txt"))
        assertFalse(GlobMatcher.matches("file[name].txt", "filen.txt"))
    }

    @Test
    fun testDoubleAsteriskLeading() {
        assertTrue(GlobMatcher.matches("**/file.txt", "file.txt"))
        assertTrue(GlobMatcher.matches("**/file.txt", "a/b/file.txt"))
        assertFalse(GlobMatcher.matches("**/file.txt", "a/b/file.md"))
    }

    @Test
    fun testIntermediateWildcard() {
        assertTrue(GlobMatcher.matches("dir/*/*.kt", "dir/sub/Main.kt"))
        assertFalse(GlobMatcher.matches("dir/*/*.kt", "dir/sub/deep/Main.kt"))
    }

    @Test
    fun testGlobToRegexDirect() {
        val regex = GlobMatcher.globToRegex("*.kt")
        assertTrue(regex.matches("Test.kt"))
        assertFalse(regex.matches("Test.java"))
    }
}

