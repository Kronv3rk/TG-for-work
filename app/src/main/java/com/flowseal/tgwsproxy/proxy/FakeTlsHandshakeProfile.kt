package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Android port of upstream proxy/fake_tls.py ClientHello profiling and
 * ServerHello construction. Deliberately produces the exact three-record
 * shape consumed by Telegram FakeTLS: ServerHello, CCS, ApplicationData.
 *
 * Neither the public key material nor the server record encrypts real TLS
 * traffic: Fake TLS is MTProto camouflage, not an actual TLS session.
 */
internal object FakeTlsHandshakeProfile {
    internal data class ClientHelloInfo(
        val cipherSuites: Set<Int>,
        val keyShares: Map<Int, Int>
    )

    private val empty = ClientHelloInfo(emptySet(), emptyMap())
    private val random = SecureRandom()
    private const val MAX_APPLICATION_DATA = 16_384
    private const val EXT_KEY_SHARE = 0x0033
    private const val GROUP_X25519 = 0x001d
    private const val GROUP_X25519_MLKEM768 = 0x11ec
    private const val HYBRID_CLIENT_SHARE_LENGTH = 1216
    private const val HYBRID_SERVER_SHARE_LENGTH = 1088 + 32

    fun clampProfileLength(length: Int): Int = length.coerceIn(64, MAX_APPLICATION_DATA)

    /**
     * Malformed fields fall back to the legacy X25519 / AES-128 answer. Never
     * allocate based on peer-provided lengths or read beyond the TLS record.
     */
    fun parseClientHello(hello: ByteArray): ClientHelloInfo {
        try {
            if (hello.size < 44 || (hello[0].toInt() and 0xff) != 0x16 ||
                (hello[5].toInt() and 0xff) != 0x01
            ) return empty

            val recordLength = u16(hello, 3)
            if (recordLength != hello.size - 5) return empty
            var p = 5 + 4 + 2 + 32
            val sessionLength = u8(hello, p++)
            if (sessionLength > 32 || p + sessionLength > hello.size) return empty
            p += sessionLength
            val suiteLen = u16(hello, p)
            p += 2
            if (suiteLen == 0 || suiteLen % 2 != 0 || p + suiteLen > hello.size) return empty
            val suites = HashSet<Int>()
            val suiteEnd = p + suiteLen
            while (p < suiteEnd) {
                suites.add(u16(hello, p))
                p += 2
            }

            val compressionCount = u8(hello, p++)
            if (compressionCount == 0 || p + compressionCount > hello.size) return empty
            p += compressionCount
            val extensionsSize = u16(hello, p)
            p += 2
            if (p + extensionsSize > hello.size) return empty
            val extEnd = p + extensionsSize
            val groups = LinkedHashMap<Int, Int>()
            while (p + 4 <= extEnd) {
                val extType = u16(hello, p)
                val extLength = u16(hello, p + 2)
                p += 4
                if (p + extLength > extEnd) return empty
                if (extType == EXT_KEY_SHARE && extLength >= 2) {
                    val listLength = u16(hello, p)
                    val shareEnd = p + 2 + listLength
                    if (shareEnd > p + extLength) return empty
                    var cursor = p + 2
                    while (cursor + 4 <= shareEnd) {
                        val group = u16(hello, cursor)
                        val length = u16(hello, cursor + 2)
                        cursor += 4
                        if (cursor + length > shareEnd) return empty
                        groups.putIfAbsent(group, length)
                        cursor += length
                    }
                    if (cursor != shareEnd) return empty
                }
                p += extLength
            }
            if (p != extEnd) return empty
            return ClientHelloInfo(suites, groups)
        } catch (_: IndexOutOfBoundsException) {
            return empty
        }
    }

    fun selectCipherSuite(info: ClientHelloInfo): Int = when {
        0x1301 in info.cipherSuites -> 0x1301
        0x1303 in info.cipherSuites -> 0x1303
        0x1302 in info.cipherSuites -> 0x1302
        else -> 0x1301
    }

    fun chooseKeyShareGroup(info: ClientHelloInfo): Int =
        if (info.keyShares[GROUP_X25519_MLKEM768] == HYBRID_CLIENT_SHARE_LENGTH) {
            GROUP_X25519_MLKEM768
        } else GROUP_X25519

    fun buildServerHello(
        secret: ByteArray,
        clientRandom: ByteArray,
        sessionId: ByteArray,
        clientHello: ByteArray,
        profiledApplicationDataLength: Int? = null
    ): ByteArray {
        require(clientRandom.size == 32 && sessionId.size <= 32)
        val info = parseClientHello(clientHello)
        val group = chooseKeyShareGroup(info)
        val keyExchange = if (group == GROUP_X25519_MLKEM768) {
            ByteArray(HYBRID_SERVER_SHARE_LENGTH).also {
                random.nextBytes(it)
                it[it.lastIndex] = (it.last().toInt() and 0x7f).toByte()
            }
        } else {
            ByteArray(32).also {
                random.nextBytes(it)
                it[it.lastIndex] = (it.last().toInt() and 0x7f).toByte()
            }
        }

        val extensions = ByteArrayOutputStream()
        // supported_versions: TLS 1.3
        writeShort(extensions, 0x002b)
        writeShort(extensions, 2)
        writeShort(extensions, 0x0304)
        // key_share: exactly one selected group, matching upstream
        writeShort(extensions, EXT_KEY_SHARE)
        writeShort(extensions, keyExchange.size + 4)
        writeShort(extensions, group)
        writeShort(extensions, keyExchange.size)
        extensions.write(keyExchange)

        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x03, 0x03))
        body.write(ByteArray(32)) // HMAC placeholder for server_random
        body.write(sessionId.size)
        body.write(sessionId)
        writeShort(body, selectCipherSuite(info))
        body.write(0) // compression
        writeShort(body, extensions.size())
        body.write(extensions.toByteArray())

        val message = ByteArrayOutputStream()
        message.write(0x02) // ServerHello
        val messageLength = body.size()
        message.write((messageLength ushr 16) and 0xff)
        writeShort(message, messageLength)
        message.write(body.toByteArray())

        val response = ByteArrayOutputStream()
        response.write(0x16)
        response.write(byteArrayOf(0x03, 0x03))
        writeShort(response, message.size())
        response.write(message.toByteArray())
        response.write(byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01))

        val appLen = profiledApplicationDataLength?.let(::clampProfileLength)
            ?: (64 + random.nextInt(97))
        response.write(byteArrayOf(0x17, 0x03, 0x03))
        writeShort(response, appLen)
        val appData = ByteArray(appLen)
        random.nextBytes(appData)
        response.write(appData)

        val answer = response.toByteArray()
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(secret, "HmacSHA256"))
        val signedRandom = hmac.doFinal(clientRandom + answer)
        System.arraycopy(signedRandom, 0, answer, 11, 32)
        return answer
    }

    private fun u8(data: ByteArray, index: Int) = data[index].toInt() and 0xff
    private fun u16(data: ByteArray, index: Int) = u8(data, index) * 256 + u8(data, index + 1)
    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}
