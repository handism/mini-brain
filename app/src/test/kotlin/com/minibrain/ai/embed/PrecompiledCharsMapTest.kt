package com.minibrain.ai.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrecompiledCharsMapTest {

    @Test
    fun `normalize handles exact matches and fallbacks`() {
        val constructor = PrecompiledCharsMap::class.java.getDeclaredConstructor(IntArray::class.java, ByteArray::class.java)
        constructor.isAccessible = true
        val trie = IntArray(300)

        // "A" -> "a"
        trie[65] = 65 or (1 shl 8) or (100 shl 10)
        trie[65 xor 100] = 0

        // "B" -> "b"
        trie[66] = 66 or (1 shl 8) or (100 shl 10)
        trie[66 xor 100] = 2

        // "!" -> ""
        trie[33] = 33 or (1 shl 8) or (100 shl 10)
        trie[33 xor 100] = 4

        // "あ" (E3 81 82) -> "a"
        val b1 = 0xE3
        val b2 = 0x81
        val b3 = 0x82
        trie[0 xor b1] = b1 or (100 shl 10)
        trie[(0 xor b1) xor 100 xor b2] = b2 or (100 shl 10)
        trie[(((0 xor b1) xor 100) xor b2) xor 100 xor b3] = b3 or (1 shl 8) or (100 shl 10)
        trie[(((((0 xor b1) xor 100) xor b2) xor 100) xor b3) xor 100] = 0

        val normalized = "a\u0000b\u0000\u0000".toByteArray(Charsets.UTF_8)
        val charsMap = constructor.newInstance(trie, normalized)

        assertEquals("a", charsMap.normalize("A"))
        assertEquals("b", charsMap.normalize("B"))
        assertEquals("", charsMap.normalize("!"))
        assertEquals("a", charsMap.normalize("あ"))
        assertEquals("ab", charsMap.normalize("A!B"))
        assertEquals("C", charsMap.normalize("C")) // Fallback to original character
        assertEquals("Ca", charsMap.normalize("Cあ")) // Fallback + transform
        assertEquals("hello a world", charsMap.normalize("hello A world"))
    }

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
