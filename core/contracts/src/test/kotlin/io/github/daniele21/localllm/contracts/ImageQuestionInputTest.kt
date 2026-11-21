package io.github.daniele21.localllm.contracts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageQuestionInputTest {
    @Test
    fun `valid question defensively copies image payload`() {
        val original = byteArrayOf(1, 2, 3)
        val input = GenerationInput.ImageQuestion("What is shown?", original)
        original[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), input.copyImageBytes())
        input.copyImageBytes()[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), input.copyImageBytes())
        assertEquals("What is shown?", input.question)
    }

    @Test
    fun `rejects oversized empty and invalid input`() {
        assertThrows(IllegalArgumentException::class.java) {
            GenerationInput.ImageQuestion("?", ByteArray(2 * 1024 * 1024 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationInput.ImageQuestion("?", byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationInput.ImageQuestion(" ", byteArrayOf(42))
        }
    }
}
