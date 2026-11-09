package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketFrameWriterTest {
    @Test
    fun frameBoundariesAndMaskingFollowRfc6455() {
        for (size in listOf(0, 1, 125, 126, 65535, 65536, 512 * 1024)) {
            val payload = ByteArray(size) { (it * 31).toByte() }
            val original = payload.clone()
            val buffer = ByteArrayOutputStream()
            WebSocketFrameWriter.writeBinary(buffer, payload, SecureRandom())
            val frame = buffer.toByteArray()
            assertEquals(0x82, frame[0].toInt() and 0xFF)
            assertEquals(0x80, frame[1].toInt() and 0x80)
            val indicator = frame[1].toInt() and 0x7f
            val lengthBytes = when (indicator) {
                in 0..125 -> 0
                126 -> 2
                else -> 8
            }
            val declared = if (lengthBytes == 0) indicator.toLong()
                else (2 until (2 + lengthBytes)).fold(0L) { value, index ->
                    (value shl 8) or (frame[index].toLong() and 0xff)
                }
            assertEquals(size.toLong(), declared)
            val maskStart = 2 + lengthBytes
            val dataStart = maskStart + 4
            assertEquals(size + dataStart, frame.size)
            val recovered = ByteArray(size) { i ->
                (frame[dataStart + i].toInt() xor frame[maskStart + (i and 3)].toInt()).toByte()
            }
            assertArrayEquals(payload, recovered)
            assertArrayEquals(original, payload)
        }
    }

    @Test
    fun largeMediaFramesAreWrittenInSmallChunks() {
        val payload = ByteArray(1024 * 1024) { 0x41.toByte() }
        val output = TrackingOutputStream()
        WebSocketFrameWriter.writeBinary(output, payload, SecureRandom())
        assertTrue("Large buffers must not be written as one giant temporary frame", output.largestWrite <= 16 * 1024)
        assertTrue(output.calls > 2)
        assertEquals(payload.size + 14, output.bytes)
    }

    @Test
    fun batchesMultiplePacketsIntoOneFlushWithoutChangingFrameBoundaries() {
        val output = TrackingOutputStream()
        val parts = listOf(ByteArray(40) { 1 }, byteArrayOf(), ByteArray(300) { 2 })
        WebSocketFrameWriter.writeBinaryBatch(output, parts, SecureRandom())
        assertEquals(0, output.flushCount)
        output.flush()
        assertEquals(1, output.flushCount)
        assertEquals(40 + 6 + 300 + 8, output.bytes)
    }

    private class TrackingOutputStream : OutputStream() {
        var largestWrite = 0
        var calls = 0
        var bytes = 0
        var flushCount = 0
        override fun flush() { flushCount++ }
        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            largestWrite = maxOf(largestWrite, length)
            calls++
            bytes += length
        }
    }
}
