#!/usr/bin/env python3
"""Read-only HTTP/2 preflight against a deliberately chosen public domain.

This checks TLS, ALPN and HEAD /api only. It cannot establish that MTProto
media requests work, and it never sends packets or authentication secrets.
"""

import argparse
from datetime import datetime, timezone
import ipaddress
import json
import re
import sys
import time
from pathlib import Path

VALID_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
NON_PUBLIC_TLDS = frozenset({"localhost", "local", "internal", "test", "invalid", "example"})


def validated_public_host(raw: str) -> str:
    host = raw.lower()
    if host != host.strip():
        raise ValueError("Whitespace is not permitted around public hostnames")
    if len(host) > 253 or not host or any(c in host for c in "/:@?#\\ "):
        raise ValueError("Provide a DNS hostname only, without scheme, port, path, credentials or spaces")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise ValueError("IP literals are not permitted for the external probe")
    labels = host.split(".")
    if len(labels) < 2 or labels[-1] in NON_PUBLIC_TLDS or not re.search(r"[a-z]", labels[-1]) or not all(
        len(label) <= 63 and VALID_LABEL.fullmatch(label) for label in labels
    ):
        raise ValueError("Provide a syntactically valid public DNS hostname")
    return host


def accepted_preflight(status: int, http_version: str) -> bool:
    return http_version == "HTTP/2" and (200 <= status <= 299 or status in (405, 501))


def probe(host: str, client_factory=None) -> dict:
    host = validated_public_host(host)
    if client_factory is None:
        import httpx

        client_factory = lambda: httpx.Client(
            http2=True, follow_redirects=False, trust_env=False, timeout=8.0
        )
    started = time.monotonic_ns()
    with client_factory() as client:
        response = client.head(f"https://{host}/api", headers={"Accept-Encoding": "identity"})
        elapsed_ms = round((time.monotonic_ns() - started) / 1_000_000, 2)
        return {
            "probe": "python-httpx-upstream-stack",
            "host": host,
            "http_version": response.http_version,
            "http_status": response.status_code,
            "elapsed_ms": elapsed_ms,
            "accepted": accepted_preflight(response.status_code, response.http_version),
            "scope": "tls_alpn_head_only",
            "observed_at": datetime.now(timezone.utc).isoformat(),
        }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Explicit public Cloudflare H2 endpoint hostname")
    parser.add_argument("--output", required=True, help="Destination JSON file for acceptance artifact")
    args = parser.parse_args(argv)
    try:
        result = probe(args.host)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"H2 preflight error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main())
