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
    }

    @Test
    fun testSingleAsterisk() {
        assertTrue(GlobMatcher.matches("*.txt", "file.txt"))
        assertFalse(GlobMatcher.matches("*.txt", "file.md"))

        // Single asterisk shouldn't match across directories
        assertFalse(GlobMatcher.matches("*.txt", "dir/file.txt"))
        assertTrue(GlobMatcher.matches("dir/*.txt", "dir/file.txt"))
        assertFalse(GlobMatcher.matches("dir/*.txt", "dir/subdir/file.txt"))
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
    }

    @Test
    fun testQuestionMark() {
        assertTrue(GlobMatcher.matches("file?.txt", "file1.txt"))
        assertTrue(GlobMatcher.matches("file?.txt", "fileA.txt"))
        assertFalse(GlobMatcher.matches("file?.txt", "file12.txt"))
        assertFalse(GlobMatcher.matches("file?.txt", "file.txt"))

        // Question mark shouldn't match directory separator
        assertFalse(GlobMatcher.matches("dir?file.txt", "dir/file.txt"))
    }

    @Test
    fun testSpecialRegexCharactersEscaped() {
        // Characters like ., +, (, ), [ should be treated literally
        assertTrue(GlobMatcher.matches("file.txt", "file.txt"))
        assertFalse(GlobMatcher.matches("file.txt", "fileXtxt"))

        assertTrue(GlobMatcher.matches("file+name.txt", "file+name.txt"))
        assertFalse(GlobMatcher.matches("file+name.txt", "fileename.txt"))

        assertTrue(GlobMatcher.matches("file(name).txt", "file(name).txt"))
        assertFalse(GlobMatcher.matches("file(name).txt", "filename.txt"))

        assertTrue(GlobMatcher.matches("file[name].txt", "file[name].txt"))
        assertFalse(GlobMatcher.matches("file[name].txt", "filen.txt"))
    }
}
