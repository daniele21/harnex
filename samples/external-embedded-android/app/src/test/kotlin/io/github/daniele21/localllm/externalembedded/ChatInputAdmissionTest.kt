package io.github.daniele21.localllm.externalembedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputAdmissionTest {
    @Test
    fun `allows ordinary text without vision setup`() {
        assertNull(ChatInputAdmission.validate("Hello", null, false))
    }

    @Test
    fun `requires ready projector before accepting image bytes`() {
        val error = ChatInputAdmission.validate("Describe this photo", byteArrayOf(1), false)
        assertEquals("Tap Prepare vision first", error?.message)
        assertTrue(error?.vision == true)
    }

    @Test
    fun `rejects empty and oversized image bytes`() {
        assertTrue(ChatInputAdmission.validate("Describe", byteArrayOf(), true)?.vision == true)
        assertTrue(
            ChatInputAdmission.validate("Describe", ByteArray(2 * 1024 * 1024 + 1), true)?.vision == true,
        )
    }

    @Test
    fun `text errors remain distinct from vision errors`() {
        val failure = ChatInputAdmission.validate("  ", null, false)
        assertEquals("Write a message before sending", failure?.message)
        assertFalse(failure?.vision ?: true)
    }

    @Test
    fun `accepts a bounded question with one ready image`() {
        assertNull(ChatInputAdmission.validate("What color is this?", byteArrayOf(1, 2, 3), true))
    }
}
