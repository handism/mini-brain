package com.minibrain.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalCaseTest {
    @Test
    fun `test EvalCase instantiation and properties`() {
        val expectedRelativePaths = listOf("path/to/file1.txt", "path/to/file2.txt")
        val evalCase = EvalCase(
            id = "case-1",
            query = "What is the meaning of life?",
            expectedRelativePaths = expectedRelativePaths
        )

        assertEquals("case-1", evalCase.id)
        assertEquals("What is the meaning of life?", evalCase.query)
        assertEquals(expectedRelativePaths, evalCase.expectedRelativePaths)
    }
}
