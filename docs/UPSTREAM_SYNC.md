# Upstream sync notes

This Android port tracks the proxy-relevant parts of `Flowseal/tg-ws-proxy`.

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
