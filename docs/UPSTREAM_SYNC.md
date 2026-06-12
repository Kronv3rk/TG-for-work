# Upstream sync notes

This Android port tracks the proxy-relevant parts of `Flowseal/tg-ws-proxy`.

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
