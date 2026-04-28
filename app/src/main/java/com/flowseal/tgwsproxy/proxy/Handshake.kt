package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.proxy.Constants.DC_IDX_POS
import com.flowseal.tgwsproxy.proxy.Constants.HANDSHAKE_LEN
import com.flowseal.tgwsproxy.proxy.Constants.IV_LEN
import com.flowseal.tgwsproxy.proxy.Constants.PREKEY_LEN
import com.flowseal.tgwsproxy.proxy.Constants.PROTO_TAG_ABRIDGED
import com.flowseal.tgwsproxy.proxy.Constants.PROTO_TAG_INTERMEDIATE
import com.flowseal.tgwsproxy.proxy.Constants.PROTO_TAG_POS
import com.flowseal.tgwsproxy.proxy.Constants.PROTO_TAG_SECURE
import com.flowseal.tgwsproxy.proxy.Constants.RESERVED_CONTINUE
import com.flowseal.tgwsproxy.proxy.Constants.RESERVED_FIRST_BYTES
import com.flowseal.tgwsproxy.proxy.Constants.RESERVED_STARTS
import com.flowseal.tgwsproxy.proxy.Constants.SKIP_LEN
import com.flowseal.tgwsproxy.proxy.Constants.ZERO_64
import java.security.MessageDigest
import java.security.SecureRandom

internal data class ParsedHandshake(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val decPrekeyAndIv: ByteArray,
)

/**
 * Tries to decrypt the 64-byte MTProto obfuscation init using [secret] and validates the
 * proto tag. Returns null on mismatch (= wrong secret / bad client). Mirrors
 * `_try_handshake` in `proxy/tg_ws_proxy.py`.
 */
internal fun tryParseHandshake(handshake: ByteArray, secret: ByteArray): ParsedHandshake? {
    require(handshake.size == HANDSHAKE_LEN)

    val decPrekeyAndIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
    val decPrekey = decPrekeyAndIv.copyOfRange(0, PREKEY_LEN)
    val decIv = decPrekeyAndIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

    val md = MessageDigest.getInstance("SHA-256")
    md.update(decPrekey)
    md.update(secret)
    val decKey = md.digest()

    val ctr = CtrCipher(decKey, decIv)
    val decrypted = ctr.update(handshake)

    val protoTag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
    val tagOk = protoTag.contentEquals(PROTO_TAG_ABRIDGED) ||
        protoTag.contentEquals(PROTO_TAG_INTERMEDIATE) ||
        protoTag.contentEquals(PROTO_TAG_SECURE)
    if (!tagOk) return null

    // little-endian signed 16-bit
    val low = decrypted[DC_IDX_POS].toInt() and 0xFF
    val high = decrypted[DC_IDX_POS + 1].toInt()
    val dcIdx = ((high shl 8) or low).toShort().toInt()
    val dcId = kotlin.math.abs(dcIdx)
    val isMedia = dcIdx < 0

    return ParsedHandshake(dcId, isMedia, protoTag, decPrekeyAndIv)
}

/**
 * Generates a random 64-byte MTProto obfuscation init for the upstream side, embedding
 * the requested proto tag and DC index. Mirrors `_generate_relay_init`.
 */
internal fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
    val rng = SecureRandom()
    val rnd = ByteArray(HANDSHAKE_LEN)
    while (true) {
        rng.nextBytes(rnd)
        if (rnd[0] in RESERVED_FIRST_BYTES) continue
        var matchesStart = false
        for (start in RESERVED_STARTS) {
            if (rnd.equalsRange(0, start)) { matchesStart = true; break }
        }
        if (matchesStart) continue
        if (rnd.equalsRange(4, RESERVED_CONTINUE)) continue
        break
    }

    val encKey = rnd.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
    val encIv = rnd.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
    val encryptor = CtrCipher(encKey, encIv)

    val dcBytes = byteArrayOf((dcIdx and 0xFF).toByte(), ((dcIdx shr 8) and 0xFF).toByte())
    val tail2 = ByteArray(2).also { rng.nextBytes(it) }
    val tailPlain = protoTag + dcBytes + tail2

    val encryptedFull = encryptor.update(rnd)
    val keystreamTail = ByteArray(8) { i -> (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte() }
    val encryptedTail = ByteArray(8) { i -> (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte() }

    val result = rnd.copyOf()
    System.arraycopy(encryptedTail, 0, result, PROTO_TAG_POS, 8)
    return result
}

/**
 * Builds the [CryptoCtx] for a connection. Mirrors `_build_crypto_ctx` in
 * `proxy/tg_ws_proxy.py`.
 */
internal fun buildCryptoCtx(
    clientDecPrekeyIv: ByteArray,
    secret: ByteArray,
    relayInit: ByteArray,
): CryptoCtx {
    val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
    val cltDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

    val md = MessageDigest.getInstance("SHA-256")
    md.update(cltDecPrekey); md.update(secret)
    val cltDecKey = md.digest()

    val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
    md.reset()
    md.update(cltEncPrekeyIv, 0, PREKEY_LEN); md.update(secret)
    val cltEncKey = md.digest()
    val cltEncIv = cltEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

    val cltDecryptor = CtrCipher(cltDecKey, cltDecIv)
    val cltEncryptor = CtrCipher(cltEncKey, cltEncIv)

    // fast-forward client decryptor past the 64-byte init
    cltDecryptor.update(ZERO_64)

    // relay side: standard obfuscation, no SHA over secret
    val relayEncKey = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
    val relayEncIv = relayInit.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)

    val relayDecPrekeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN).reversedArray()
    val relayDecKey = relayDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
    val relayDecIv = relayDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

    val tgEncryptor = CtrCipher(relayEncKey, relayEncIv)
    val tgDecryptor = CtrCipher(relayDecKey, relayDecIv)

    tgEncryptor.update(ZERO_64)

    return CryptoCtx(cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
}
