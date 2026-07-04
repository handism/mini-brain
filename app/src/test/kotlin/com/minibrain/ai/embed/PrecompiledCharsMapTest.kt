package com.minibrain.ai.embed

import org.junit.Assert.assertThrows
import org.junit.Test

class PrecompiledCharsMapTest {

    @Test
    fun `parse throws when byte array is too short`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrecompiledCharsMap.parse(ByteArray(3))
        }
    }

    @Test
    fun `parse throws when trieSize is invalid`() {
        assertThrows(IllegalArgumentException::class.java) {
            // Provide a byte array of size 4 where the size implies a large trie size
            PrecompiledCharsMap.parse(ByteArray(4) { 0xFF.toByte() })
        }
    }
}
