package com.flowseal.tgwsproxy.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateful AES-CTR streaming cipher. `update(bytes)` keeps the keystream counter
 * across calls (matches `cryptography.hazmat`'s `CTR` mode behaviour in the upstream
 * Python project).
 */
internal class CtrCipher(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray =
        cipher.update(data, offset, length) ?: ByteArray(0)

    fun update(data: ByteArray): ByteArray = cipher.update(data) ?: ByteArray(0)
}

/**
 * Crypto context for one client connection. Mirrors `CryptoCtx` in `proxy/bridge.py`.
 *
 * - `cltDec` decrypts data coming from the local Telegram client
 * - `cltEnc` encrypts data going back to the local Telegram client
 * - `tgEnc` encrypts data going to the upstream Telegram DC
 * - `tgDec` decrypts data coming from the upstream Telegram DC
 */
internal class CryptoCtx(
    val cltDec: CtrCipher,
    val cltEnc: CtrCipher,
    val tgEnc: CtrCipher,
    val tgDec: CtrCipher,
)
