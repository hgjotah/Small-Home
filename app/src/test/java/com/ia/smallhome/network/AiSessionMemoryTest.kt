package com.ia.smallhome.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSessionMemoryTest {
    @Test
    fun `session start request response and end are kept only in memory`() {
        val memory = AiSessionMemory()

        memory.start("session-1")
        assertTrue(memory.contains("session-1"))
        assertEquals(listOf(ChatMessage("user", "Hola")), memory.request("session-1", "Hola"))
        memory.response("session-1", "Respuesta")
        assertEquals(
            listOf(ChatMessage("user", "Hola"), ChatMessage("assistant", "Respuesta")),
            memory.messages("session-1"),
        )

        memory.end("session-1")
        assertFalse(memory.contains("session-1"))
        assertTrue(memory.messages("session-1").isEmpty())
    }

    @Test
    fun `session keeps only recent context and limits prompt length`() {
        val memory = AiSessionMemory(maxMessages = 4)
        memory.start("session-2")
        assertEquals(320, memory.request("session-2", "x".repeat(400)).single().content.length)
        memory.response("session-2", "a0")
        repeat(2) { offset ->
            val index = offset + 1
            memory.request("session-2", "u$index")
            memory.response("session-2", "a$index")
        }

        val messages = memory.messages("session-2")
        assertEquals(4, messages.size)
        assertEquals("u1", messages.first().content)
        assertEquals("a2", messages.last().content)
    }
}
