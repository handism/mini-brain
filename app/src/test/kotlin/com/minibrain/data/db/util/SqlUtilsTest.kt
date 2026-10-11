package com.minibrain.data.db.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SqlUtilsTest {

    @Test
    fun escapeForLike_escapesWildcards() {
        assertEquals("test\\_string", SqlUtils.escapeForLike("test_string"))
        assertEquals("test\\%string", SqlUtils.escapeForLike("test%string"))
        assertEquals("test\\\\string", SqlUtils.escapeForLike("test\\string"))
        assertEquals("no-wildcards-here", SqlUtils.escapeForLike("no-wildcards-here"))
        assertEquals("\\%\\_\\\\", SqlUtils.escapeForLike("%_\\"))
    }
}
