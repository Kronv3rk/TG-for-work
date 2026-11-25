package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProxySecretStoreTest {
    private val secret = "0123456789abcdef".repeat(2)

    @Test
    fun failedWriteCannotExposeAnUndurableSecretEvenIfPreferencesMemoryWasUpdated() {
        val store = ProxySecretStore()
        var memory: String? = null
        var writable = false
        var generated = 0
        fun resolve() = store.resolve({ memory }, { true }, { generated++; secret }, {
            memory = it
            writable
        })
        assertThrows(IllegalStateException::class.java) { resolve() }
        assertThrows(IllegalStateException::class.java) { resolve() }
        writable = true
        assertEquals(secret, resolve())
        assertEquals(1, generated)
    }

    @Test
    fun existingSecretIsPreservedWithoutRegeneration() {
        assertEquals(secret, ProxySecretStore().resolve({ secret }, { true },
            { error("Must not generate") }, { error("Must not write") }))
    }

    @Test
    fun lockedStorageDoesNotGenerateSecret() {
        assertThrows(IllegalStateException::class.java) {
            ProxySecretStore().resolve({ null }, { false }, { error("Must not generate") }, { true })
        }
    }
}
