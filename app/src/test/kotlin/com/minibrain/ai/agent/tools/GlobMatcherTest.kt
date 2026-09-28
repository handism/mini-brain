package com.minibrain.ai.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GlobMatcherTest(
    private val pattern: String,
    private val path: String,
    private val expectedMatch: Boolean
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: pattern='{0}', path='{1}', expected={2}")
        fun data(): Collection<Array<Any>> {
            return listOf(
                // Exact Match
                arrayOf("file.txt", "file.txt", true),
                arrayOf("file.txt", "file2.txt", false),
                arrayOf("dir/file.txt", "dir/file.txt", true),
                arrayOf("dir/file.txt", "dir2/file.txt", false),
                arrayOf("proj/README.md", "proj/README.md", true),
                arrayOf("proj/README.md", "proj/readme.md", false),

                // Single Asterisk
                arrayOf("*.txt", "file.txt", true),
                arrayOf("*.txt", "file.md", false),
                arrayOf("*.txt", "dir/file.txt", false), // Shouldn't match across directories
                arrayOf("dir/*.txt", "dir/file.txt", true),
                arrayOf("dir/*.txt", "dir/subdir/file.txt", false),
                arrayOf("2026/06/*", "2026/06/01.md", true),
                arrayOf("2026/06/*", "2026/06/日記.md", true),
                arrayOf("2026/06/*", "2026/06/sub/x.md", false),
                arrayOf("2026/06/*", "2026/07/01.md", false),

                // Double Asterisk
                arrayOf("**/*.txt", "file.txt", true),
                arrayOf("**/*.txt", "dir/file.txt", true),
                arrayOf("**/*.txt", "dir/subdir/file.txt", true),
                arrayOf("src/**/*.kt", "src/Main.kt", true),
                arrayOf("src/**/*.kt", "src/com/example/Main.kt", true),
                arrayOf("src/**/*.kt", "test/Main.kt", false),
                arrayOf("src/**/*.kt", "src/com/example/Main.java", false),
                arrayOf("proj/**/*.md", "proj/sub/file.md", true),
                arrayOf("proj/**/*.md", "proj/a/b/c.md", true),
                arrayOf("proj/**", "proj/README.md", true),
                arrayOf("proj/**", "proj/sub/deep.md", true),
                arrayOf("**/file.txt", "file.txt", true),
                arrayOf("**/file.txt", "a/b/file.txt", true),
                arrayOf("**/file.txt", "a/b/file.md", false),

                // Intermediate Wildcard
                arrayOf("dir/*/*.kt", "dir/sub/Main.kt", true),
                arrayOf("dir/*/*.kt", "dir/sub/deep/Main.kt", false),

                // Question Mark
                arrayOf("file?.txt", "file1.txt", true),
                arrayOf("file?.txt", "fileA.txt", true),
                arrayOf("file?.txt", "file12.txt", false),
                arrayOf("file?.txt", "file.txt", false),
                arrayOf("2026/0?.md", "2026/06.md", true),
                arrayOf("2026/0?.md", "2026/06/x.md", false),
                arrayOf("dir?file.txt", "dir/file.txt", false), // Shouldn't match directory separator

                // Japanese Path
                arrayOf("日記/*", "日記/2026-06.md", true),
                arrayOf("日記/*", "他/2026-06.md", false),

                // Escaped Special Regex Characters
                arrayOf("file.txt", "fileXtxt", false),
                arrayOf("2026/06/01.md", "2026/06/01Xmd", false),
                arrayOf("file+name.txt", "file+name.txt", true),
                arrayOf("file+name.txt", "fileename.txt", false),
                arrayOf("file(name).txt", "file(name).txt", true),
                arrayOf("file(name).txt", "filename.txt", false),
                arrayOf("file[name].txt", "file[name].txt", true),
                arrayOf("file[name].txt", "filen.txt", false),

                // Glob to Regex direct check replacement (implicit via matches)
                arrayOf("*.kt", "Test.kt", true),
                arrayOf("*.kt", "Test.java", false)
            )
        }
    }

    @Test
    fun testMatches() {
        assertEquals(expectedMatch, GlobMatcher.matches(pattern, path))
    }
}
