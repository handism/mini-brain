package com.minibrain.data.db.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SqlUtilsTest {
    @Test
    fun testEscapeForLike() {
        assertEquals("\\%", SqlUtils.escapeForLike("%"))
        assertEquals("\\_", SqlUtils.escapeForLike("_"))
        assertEquals("\\\\", SqlUtils.escapeForLike("\\"))
        assertEquals("\\\\\\%", SqlUtils.escapeForLike("\\%"))
        assertEquals("no-wildcards-here", SqlUtils.escapeForLike("no-wildcards-here"))
    }
}
