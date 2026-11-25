package com.flowseal.tgwsproxy.proxy

internal class ProxySecretStore {
    private var pending: String? = null

    @Synchronized
    fun resolve(
        read: () -> String?,
        storageUnlocked: () -> Boolean,
        generate: () -> String,
        persist: (String) -> Boolean
    ): String {
        val existing = read()?.takeIf(ProxyRuntime::isValidSecret)
        if (pending == null && existing != null) return existing
        check(storageUnlocked()) { "Storage is locked; refusing to generate a new proxy secret" }
        val secret = existing ?: pending ?: generate()
        check(ProxyRuntime.isValidSecret(secret)) { "Invalid generated proxy secret" }
        // SharedPreferences can update its memory cache before a failed disk write.
        pending = secret
        check(persist(secret)) { "Could not persist proxy secret" }
        pending = null
        return secret
    }
}
