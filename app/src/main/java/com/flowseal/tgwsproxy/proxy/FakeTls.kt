package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor
import kotlin.math.abs

data class ClientStreams(
    val handshake: ByteArray,
    val input: InputStream,
    val output: OutputStream
)

class FakeTls(
    private val secret: ByteArray,
    private val maskingDomain: String,
    private val profiledApplicationDataLength: Int? = null
) {

    fun readClientStreams(rawInput: BufferedInputStream, rawOutput: BufferedOutputStream): ClientStreams? {
        val first = rawInput.read()
        if (first < 0) return null

        if (maskingDomain.isNotBlank()) {
            if (first == TLS_RECORD_HANDSHAKE) {
                val headerRest = rawInput.readExact(4)
                val tlsHeader = byteArrayOf(first.toByte()) + headerRest
                val recordLength = ((tlsHeader[3].toInt() and 0xFF) shl 8) or
                    (tlsHeader[4].toInt() and 0xFF)
                val recordBody = rawInput.readExact(recordLength)
                val clientHello = tlsHeader + recordBody
                val result = verifyClientHello(clientHello)
                // A valid Hello repeated within the timestamp window is a replay
                // probe. Behave exactly like the camouflage website for both invalid
                // and replayed requests (upstream fix #1532).
                if (result == null || !replayCache.firstSeen(result.clientRandom)) {
                    proxyToMaskingDomain(rawInput, rawOutput, clientHello)
                    return null
                }
                // Follow upstream's 8–24 ms ServerHello pacing and reflect the
                // actual cipher/key_share offered in this authenticated ClientHello.
                Thread.sleep(8L + java.util.concurrent.ThreadLocalRandom.current().nextLong(17L))
                rawOutput.write(
                    FakeTlsHandshakeProfile.buildServerHello(
                        secret, result.clientRandom, result.sessionId, clientHello,
                        profiledApplicationDataLength
                    )
                )
                rawOutput.flush()
                val fakeInput = FakeTlsInputStream(rawInput)
                val fakeOutput = FakeTlsOutputStream(rawOutput)
                return ClientStreams(fakeInput.readExact(HANDSHAKE_LEN), fakeInput, fakeOutput)
            }

            val redirect = "HTTP/1.1 301 Moved Permanently\r\n" +
                "Location: https://$maskingDomain/\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            rawOutput.write(redirect.toByteArray(Charsets.US_ASCII))
            rawOutput.flush()
            return null
        }

        return ClientStreams(byteArrayOf(first.toByte()) + rawInput.readExact(HANDSHAKE_LEN - 1), rawInput, rawOutput)
    }

    private fun verifyClientHello(data: ByteArray): VerifyResult? {
        if (data.size < 43) return null
        if ((data[0].toInt() and 0xFF) != TLS_RECORD_HANDSHAKE) return null
        if ((data[5].toInt() and 0xFF) != 0x01) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)
        val zeroed = data.copyOf()
        for (i in CLIENT_RANDOM_OFFSET until CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN) {
            zeroed[i] = 0
        }
        val expected = hmacSha256(secret, zeroed)
        // Match upstream hmac.compare_digest: do not leak the index of the
        // first mismatched HMAC byte through early-return timing.
        if (!java.security.MessageDigest.isEqual(
                expected.copyOfRange(0, 28), clientRandom.copyOfRange(0, 28)
            )
        ) return null
        val timestampBytes = ByteArray(4) { i -> clientRandom[28 + i] xor expected[28 + i] }
        val timestamp = littleEndianInt(timestampBytes).toLong().and(0xFFFFFFFFL)
        val now = System.currentTimeMillis() / 1000L
        if (abs(now - timestamp) > TIMESTAMP_TOLERANCE) return null

        val sessionId = if (data.size >= SESSION_ID_OFFSET + SESSION_ID_LEN &&
            (data[43].toInt() and 0xFF) == SESSION_ID_LEN
        ) {
            data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        } else {
            ByteArray(SESSION_ID_LEN)
        }
        return VerifyResult(clientRandom, sessionId)
    }

    private fun proxyToMaskingDomain(
        rawInput: BufferedInputStream,
        rawOutput: BufferedOutputStream,
        initialData: ByteArray
    ) {
        maskingForwardingGate.withPermit {
            runCatching {
                Socket().use { upstream ->
                    upstream.tcpNoDelay = true
                    upstream.connect(InetSocketAddress(maskingDomain, 443), 10_000)
                    upstream.soTimeout = 15_000
                    val upstreamInput = BufferedInputStream(upstream.getInputStream())
                    val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
                    upstreamOutput.write(initialData)
                    upstreamOutput.flush()

                    val up = Thread({
                        runCatching { rawInput.copyTo(upstreamOutput, BUFFER_SIZE) }
                    }, "masking-client-to-site").apply { isDaemon = true }
                    val down = Thread({
                        runCatching { upstreamInput.copyTo(rawOutput, BUFFER_SIZE) }
                    }, "masking-site-to-client").apply { isDaemon = true }
                    up.start()
                    down.start()
                    // Never let a malformed or idle client tie up two threads forever.
                    up.join(15_000)
                    down.join(15_000)
                }
            }
        }
    }

    private data class VerifyResult(val clientRandom: ByteArray, val sessionId: ByteArray)

    companion object {
        private val replayCache = FakeTlsReplayCache()
        private val maskingForwardingGate = ConnectionGate(8)
        const val TLS_RECORD_HANDSHAKE = 0x16
        private const val TLS_RECORD_APPDATA = 0x17
        private const val HANDSHAKE_LEN = 64
        private const val CLIENT_RANDOM_OFFSET = 11
        private const val CLIENT_RANDOM_LEN = 32
        private const val SESSION_ID_OFFSET = 44
        private const val SESSION_ID_LEN = 32
        private const val TIMESTAMP_TOLERANCE = 120L
        private const val TLS_APPDATA_MAX = 16_384
        private const val BUFFER_SIZE = 16 * 1024
        private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        fun wrapTlsRecords(data: ByteArray): ByteArray {
            // Write directly to one buffer; repeated byte-array concatenation was
            // quadratic for large media payloads split into many TLS records.
            val recordCount = (data.size + TLS_APPDATA_MAX - 1) / TLS_APPDATA_MAX
            val output = ByteArrayOutputStream(data.size + recordCount * 5)
            var offset = 0
            while (offset < data.size) {
                val length = minOf(TLS_APPDATA_MAX, data.size - offset)
                output.write(byteArrayOf(
                    TLS_RECORD_APPDATA.toByte(),
                    0x03,
                    0x03,
                    ((length shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte()
                ))
                output.write(data, offset, length)
                offset += length
            }
            return output.toByteArray()
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun littleEndianInt(data: ByteArray): Int {
            return (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
        }

    }
}

private fun InputStream.readExact(length: Int): ByteArray {
    val data = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(data, offset, length - offset)
        if (read < 0) throw EOFException("Unexpected end of stream")
        offset += read
    }
    return data
}

class FakeTlsInputStream(private val raw: InputStream) : InputStream() {
    private var buffer = ByteArray(0)
    private var bufferOffset = 0
    private var readLeft = 0

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > target.size - length) {
            throw IndexOutOfBoundsException("Invalid output buffer range")
        }
        if (length == 0) return 0
        if (bufferOffset >= buffer.size) {
            buffer = readTlsPayload()
            bufferOffset = 0
            if (buffer.isEmpty()) return -1
        }
        val count = minOf(length, buffer.size - bufferOffset)
        System.arraycopy(buffer, bufferOffset, target, offset, count)
        bufferOffset += count
        if (bufferOffset >= buffer.size) {
            buffer = ByteArray(0)
            bufferOffset = 0
        }
        return count
    }

    private fun readTlsPayload(): ByteArray {
        if (readLeft > 0) {
            val chunk = ByteArray(minOf(readLeft, BUFFER_SIZE))
            val read = raw.read(chunk)
            if (read <= 0) return ByteArray(0)
            readLeft -= read
            return chunk.copyOf(read)
        }

        while (true) {
            // EOF at a record boundary is normal. A truncated header still
            // throws EOFException via readExact, preserving corruption signals.
            val type = raw.read()
            if (type < 0) return ByteArray(0)
            val header = raw.readExact(4)
            val recordLength = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == 0x14) {
                if (recordLength > 0) raw.readExact(recordLength)
                continue
            }
            if (type != 0x17) return ByteArray(0)
            if (recordLength > TLS_RECORD_MAX) return ByteArray(0)
            // A legal zero-length application record is not EOF.
            if (recordLength == 0) continue
            val readLength = minOf(recordLength, BUFFER_SIZE)
            val data = ByteArray(readLength)
            val read = raw.read(data)
            if (read <= 0) return ByteArray(0)
            readLeft = recordLength - read
            return data.copyOf(read)
        }
    }

    private companion object {
        private const val TLS_RECORD_MAX = 65_535
        private const val BUFFER_SIZE = 16 * 1024
    }
}

class FakeTlsOutputStream(private val raw: OutputStream) : OutputStream() {
    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()))
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > data.size - length) {
            throw IndexOutOfBoundsException("Invalid input buffer range")
        }
        if (length == 0) return
        raw.write(FakeTls.wrapTlsRecords(data.copyOfRange(offset, offset + length)))
    }

    override fun flush() {
        raw.flush()
    }

    override fun close() {
        raw.close()
    }
}
