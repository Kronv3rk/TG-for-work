package com.flowseal.tgwsproxy.proxy

/** Mirrors upstream --no-secure for Cloudflare WS/Worker only. */
internal object CloudflareTransportMode {
    fun port(secure: Boolean): Int = if (secure) 443 else 80

    /** Domain fronting needs SNI and is never sent over plaintext. */
    fun isValid(secure: Boolean, sni: String?): Boolean = secure || sni == null
}
