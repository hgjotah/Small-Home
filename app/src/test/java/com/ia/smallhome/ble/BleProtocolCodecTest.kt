package com.ia.smallhome.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolCodecTest {
    @Test
    fun `encoder adds newline and uses chunks of at most eighteen bytes`() {
        val chunks = BleProtocolCodec.encode("""{"type":"heartbeat","extra":"${"x".repeat(40)}"}""")
        assertTrue(chunks.all { it.size <= 18 })
        assertEquals('\n'.code.toByte(), chunks.last().last())
    }

    @Test
    fun `decoder accepts json in one chunk`() {
        val result = BleStreamDecoder().append("{\"a\":1}\n".toByteArray())
        assertEquals(listOf("{\"a\":1}"), result.frames)
    }

    @Test
    fun `decoder joins two and many chunks`() {
        val two = BleStreamDecoder()
        assertTrue(two.append("{\"a\":".toByteArray()).frames.isEmpty())
        assertEquals(listOf("{\"a\":1}"), two.append("1}\n".toByteArray()).frames)

        val many = BleStreamDecoder()
        val wire = "{\"text\":\"mensaje largo\"}\n".toByteArray()
        val frames = wire.map { many.append(byteArrayOf(it)).frames }.flatten()
        assertEquals(listOf("{\"text\":\"mensaje largo\"}"), frames)
    }

    @Test
    fun `decoder returns two json messages from one callback`() {
        val result = BleStreamDecoder().append("{\"a\":1}\n{\"b\":2}\n".toByteArray())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), result.frames)
    }

    @Test
    fun `utf8 remains correct when a code point is split between chunks`() {
        val decoder = BleStreamDecoder()
        val wire = "{\"text\":\"España 🏠\"}\n".toByteArray(Charsets.UTF_8)
        val split = wire.indexOfFirst { it.toInt() and 0x80 != 0 } + 1
        assertTrue(decoder.append(wire.copyOfRange(0, split)).frames.isEmpty())
        assertEquals(listOf("{\"text\":\"España 🏠\"}"), decoder.append(wire.copyOfRange(split, wire.size)).frames)
    }

    @Test
    fun `empty final newline does not create a phantom frame`() {
        val result = BleStreamDecoder().append("{\"a\":1}\n\n".toByteArray())
        assertEquals(1, result.frames.size)
    }

    @Test
    fun `corrupt utf8 and oversized frame are discarded safely`() {
        val invalid = BleStreamDecoder().append(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()))
        assertTrue(invalid.discardedFrame)
        assertTrue(invalid.frames.isEmpty())

        val oversizedDecoder = BleStreamDecoder(maxBufferBytes = 8)
        val oversized = oversizedDecoder.append("123456789".toByteArray())
        assertTrue(oversized.discardedFrame)
        assertEquals(0, oversizedDecoder.bufferedByteCount())
        assertTrue(oversizedDecoder.append("tail".toByteArray()).discardedFrame)
        val recovered = oversizedDecoder.append("\n{\"a\":1}\n".toByteArray())
        assertEquals(listOf("{\"a\":1}"), recovered.frames)
    }

    @Test
    fun `reset on reconnect drops a partial frame`() {
        val decoder = BleStreamDecoder()
        decoder.append("{\"old\":".toByteArray())
        assertTrue(decoder.bufferedByteCount() > 0)
        decoder.reset()
        val result = decoder.append("{\"new\":1}\n".toByteArray())
        assertEquals(listOf("{\"new\":1}"), result.frames)
        assertFalse(result.discardedFrame)
    }
}
