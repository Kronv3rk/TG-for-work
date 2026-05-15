package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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

class FakeTls(private val secret: ByteArray, private val maskingDomain: String) {
    private val random = SecureRandom()

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
                if (result == null) {
                    proxyToMaskingDomain(rawInput, rawOutput, clientHello)
                    return null
                }
                rawOutput.write(buildServerHello(result.clientRandom, result.sessionId))
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
        for (i in 0 until 28) {
            if (expected[i] != clientRandom[i]) return null
        }
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

    private fun buildServerHello(clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val hello = SERVER_HELLO_TEMPLATE.copyOf()
        System.arraycopy(sessionId, 0, hello, SERVER_HELLO_SESSION_ID_OFFSET, SESSION_ID_LEN)
        val publicKey = ByteArray(32).also(random::nextBytes)
        System.arraycopy(publicKey, 0, hello, SERVER_HELLO_PUBLIC_KEY_OFFSET, publicKey.size)

        val encryptedData = ByteArray(random.nextInt(201) + 1900).also(random::nextBytes)
        val appRecord = byteArrayOf(
            TLS_RECORD_APPDATA.toByte(),
            0x03,
            0x03,
            ((encryptedData.size shr 8) and 0xFF).toByte(),
            (encryptedData.size and 0xFF).toByte()
        ) + encryptedData
        val response = hello + CCS_FRAME + appRecord
        val serverRandom = hmacSha256(secret, clientRandom + response)
        return response.copyOf().also {
            System.arraycopy(serverRandom, 0, it, SERVER_HELLO_RANDOM_OFFSET, 32)
        }
    }

    private fun proxyToMaskingDomain(
        rawInput: BufferedInputStream,
        rawOutput: BufferedOutputStream,
        initialData: ByteArray
    ) {
        runCatching {
            Socket().use { upstream ->
                upstream.tcpNoDelay = true
                upstream.connect(InetSocketAddress(maskingDomain, 443), 10_000)
                val upstreamInput = BufferedInputStream(upstream.getInputStream())
                val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
                upstreamOutput.write(initialData)
                upstreamOutput.flush()

                val up = Thread { rawInput.copyTo(upstreamOutput, BUFFER_SIZE) }
                val down = Thread { upstreamInput.copyTo(rawOutput, BUFFER_SIZE) }
                up.start()
                down.start()
                up.join()
                down.join()
            }
        }
    }

    private data class VerifyResult(val clientRandom: ByteArray, val sessionId: ByteArray)

    companion object {
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
        private const val SERVER_HELLO_RANDOM_OFFSET = 11
        private const val SERVER_HELLO_SESSION_ID_OFFSET = 44
        private const val SERVER_HELLO_PUBLIC_KEY_OFFSET = 89
        private val SERVER_HELLO_TEMPLATE = byteArrayOf(
            0x16, 0x03, 0x03, 0x00, 0x7a,
            0x02, 0x00, 0x00, 0x76,
            0x03, 0x03,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x20,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x13, 0x01, 0x00,
            0x00, 0x2e,
            0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x00, 0x2b, 0x00, 0x02, 0x03, 0x04
        )

        fun wrapTlsRecords(data: ByteArray): ByteArray {
            val parts = ArrayList<ByteArray>()
            var offset = 0
            while (offset < data.size) {
                val length = minOf(TLS_APPDATA_MAX, data.size - offset)
                parts += byteArrayOf(
                    TLS_RECORD_APPDATA.toByte(),
                    0x03,
                    0x03,
                    ((length shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte()
                ) + data.copyOfRange(offset, offset + length)
                offset += length
            }
            return parts.fold(ByteArray(0)) { acc, part -> acc + part }
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
            val header = raw.readExact(5)
            val type = header[0].toInt() and 0xFF
            val recordLength = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
            if (type == 0x14) {
                if (recordLength > 0) raw.readExact(recordLength)
                continue
            }
            if (type != 0x17) return ByteArray(0)
            if (recordLength <= 0 || recordLength > TLS_RECORD_MAX) return ByteArray(0)
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
        raw.write(FakeTls.wrapTlsRecords(data.copyOfRange(offset, offset + length)))
    }

    override fun flush() {
        raw.flush()
    }

    override fun close() {
        raw.close()
    }
}
