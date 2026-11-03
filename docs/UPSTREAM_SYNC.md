# Upstream sync notes

This Android port tracks the proxy-relevant parts of `Flowseal/tg-ws-proxy`.

## Android 1.9.1 maintenance (2026-11-03)

The Android proxy still tracks the networking design of upstream v1.10.4.
Upstream v1.11.4 (released 2026-11-01) adds substantial transports and
routing changes, including HTTP/2; this Android maintenance release does
**not** claim to port those features.

Android-specific fixes: strict syntax validation of custom IPv6 DC addresses,
correct Fake TLS stream EOF and zero-length behavior, and linear-time
encoding of large TLS records. Existing secrets and proxy links are preserved.

## Synced from TG WS Proxy v1.10.4 (app v1.9.0)

- **WebSocket pool** (`proxy/pool.py` → `WsPool.kt`): pre-opened relay sockets per
  (DC, media), rotated after 120 s, refill backoff 1 s → 1 h, no refill while the relay
  address is blocked. Android limits on top of upstream: Wi-Fi only, and a pair that has
  not been used for 5 min is closed and no longer refilled. A stale pooled socket that
  yields no data does not cool the route down. Upstream's pool TODO (wrong `is_media`
  domain) does not apply: the pool is keyed by (DC, media) and dials that pair's own
  domain order.
- **Relay address timeout** (upstream `ip_fail_until`, `9ff95d1`/`5eb7e00`): a WebSocket
  connect *timeout* blocks that relay address for 1 h (both DC2 and DC4 sit behind it), stops
  trying the other domain, and routes straight to Cloudflare/Worker. Only applies when one
  of those is configured. Direct TCP dials different addresses and is untouched, and
  fronting is kept (SNI filtering also looks like a timeout); the block is lifted on a
  network change. The base connect timeout stays 8 s (upstream 5 s) until
  latency data shows it is safe on cellular.
- **Domain fronting** (`c8f6f8c`, `a0545cc`): `RouteMethod.FRONTING`, TLS to the relay
  with a user-supplied SNI and the Telegram Host header, one attempt, 30 min rest after a
  failure. Unlike upstream the SNI is not hard-coded (`sprinthost.ru`) and the route is
  off by default. No custom TrustManager: the chain is still validated and the raw
  SSLSocket does not verify hostnames. Upstream later moved fronting inside the pool;
  that is not ported.
- **O(N) packet splitter** (`49e62ca`): `MtprotoPacketSplitter` consumes by offset.
- **Cloudflare fallback pool** refreshed to 20 domains.
- **Domain censoring** (`c02398f`): applied to the copied journal only.

Already equivalent here: no splitter on the Worker route (`ecf3d6f`), shuffled
Cloudflare domains (`dfdb993`), user CF domain is never overwritten by the fetched list
(`2995ae7`).

Not ported (desktop-only): SSL-verification switch and certifi, tray/update dialogs,
macOS/Linux packaging, log file rotation, Telegram test DCs.

## Synced / improved in v1.7.0

- **Adaptive connect timeout** ported from upstream: a route that recently failed gets a short
  connect budget (probation) instead of the full timeout, so a flaky path is abandoned quickly
  and stuck media reaches a working route faster (`RoutePlanner.connectTimeoutMs`).
- **Capped Cloudflare attempts** per connection so a fully blocked CF pool cannot stall media
  while every fallback domain is tried in turn.
- **WebSocket keepalive** is an enhancement beyond upstream (which neither pings nor sets a
  data-phase timeout): the relay is pinged while idle and dead half-open connections are detected
  and recycled, with a separate stall guard that keeps slow-but-progressing media frames alive.
- **Refreshed Cloudflare fallback pool** to match the current upstream `cfproxy-domains.txt`
  (15 domains).
- Reconnect steering: a route that connects but returns no bytes is cooled down so the next
  client reconnect picks a different route.

## Synced from TG WS Proxy v1.6.6

- Expanded the built-in Cloudflare proxy fallback domain pool from the upstream `.github/cfproxy-domains.txt` list.
- Added validation for Cloudflare domains fetched from GitHub before replacing the active fallback pool.
- Added support for whitespace-separated domain payloads, so both one-domain-per-line and single-line lists are accepted.
- Kept the current domain pool when a fetched payload is empty or has too few valid domains.

Desktop-only changes from upstream v1.6.6 are intentionally not ported:

- RPM packaging.
- Docker `TG_WS_PROXY_SECRET` support.
- Desktop settings-window UI changes.
- CLI argument parsing fixes that do not exist in the Android UI path.
