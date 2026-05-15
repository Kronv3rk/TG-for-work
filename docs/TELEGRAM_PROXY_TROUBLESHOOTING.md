# Telegram proxy troubleshooting

Telegram shows the dialog "the proxy server uses incorrect settings and will be disconnected" when the configured proxy endpoint does not complete the protocol handshake expected by Telegram.

## Fast checks

1. Make sure Telegram receives a host only, not a full URL: use `example.com`, not `https://example.com/path`.
2. Make sure the port is numeric and in the `1..65535` range.
3. For MTProto proxies, make sure the secret is hex encoded:
   - classic secret: 32 hex characters;
   - TLS-style secret: starts with `dd` or `ee`, then has an even number of hex characters and at least 16 bytes of key material.
4. Make sure the proxy server and the proxy type match. A SOCKS/HTTP/WebSocket endpoint will not work if Telegram is configured as MTProto, and the reverse is also true.
5. If the proxy is local on Android, make sure the local server is already listening before Telegram switches to it.
6. Check server logs for handshake failures. A TCP connection alone is not enough; Telegram disables the proxy if the protocol negotiation fails.

## What was added to the project

`TelegramProxyConfig` now performs basic client-side validation before settings can be passed deeper into proxy code. This catches the most common configuration mistakes early and makes the failure explicit instead of letting Telegram show a generic proxy error later.
