package com.ia.smallhome.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BleDecodeResult(
    val frames: List<String>,
    val discardedFrame: Boolean = false,
)

object BleProtocolCodec {
    const val CHUNK_BYTES = 18
    const val DEFAULT_MAX_BUFFER_BYTES = 8_192

    fun encode(json: String, chunkBytes: Int = CHUNK_BYTES): List<ByteArray> {
        require(chunkBytes > 0)
        val compactLine = json.trimEnd('\r', '\n') + "\n"
        return compactLine.toByteArray(Charsets.UTF_8).asListOfChunks(chunkBytes)
    }

    private fun ByteArray.asListOfChunks(size: Int): List<ByteArray> =
        indices.step(size).map { start -> copyOfRange(start, minOf(start + size, this.size)) }
}

class BleStreamDecoder(
    private val maxBufferBytes: Int = BleProtocolCodec.DEFAULT_MAX_BUFFER_BYTES,
) {
    private val buffer = ByteArrayOutputStream()
    private var discardingOversizedFrame = false

    @Synchronized
    fun append(chunk: ByteArray): BleDecodeResult {
        if (chunk.isEmpty()) return BleDecodeResult(emptyList())
        var input = chunk
        var discarded = false
        if (discardingOversizedFrame) {
            val newline = input.indexOf('\n'.code.toByte())
            if (newline < 0) return BleDecodeResult(emptyList(), discardedFrame = true)
            discardingOversizedFrame = false
            discarded = true
            input = input.copyOfRange(newline + 1, input.size)
            if (input.isEmpty()) return BleDecodeResult(emptyList(), discardedFrame = true)
        }

        buffer.write(input)
        val bytes = buffer.toByteArray()
        val frames = mutableListOf<String>()
        var frameStart = 0

        bytes.indices.forEach { index ->
            if (bytes[index] != '\n'.code.toByte()) return@forEach
            val size = index - frameStart
            if (size > maxBufferBytes) {
                discarded = true
            } else {
                val frameBytes = bytes.copyOfRange(frameStart, index).dropTrailingCarriageReturn()
                if (frameBytes.isNotEmpty()) {
                    decodeUtf8(frameBytes)?.let(frames::add) ?: run { discarded = true }
                }
            }
            frameStart = index + 1
        }

        buffer.reset()
        if (frameStart < bytes.size) {
            val remainder = bytes.copyOfRange(frameStart, bytes.size)
            if (remainder.size > maxBufferBytes) {
                discarded = true
                discardingOversizedFrame = true
            } else {
                buffer.write(remainder)
            }
        }
        return BleDecodeResult(frames, discarded)
    }

    @Synchronized
    fun reset() {
        buffer.reset()
        discardingOversizedFrame = false
    }

    @Synchronized
    fun bufferedByteCount(): Int = buffer.size()

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun ByteArray.dropTrailingCarriageReturn(): ByteArray =
        if (lastOrNull() == '\r'.code.toByte()) copyOf(size - 1) else this
}
