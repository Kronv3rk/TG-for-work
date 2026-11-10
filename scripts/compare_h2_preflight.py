#!/usr/bin/env python3
"""Compare transport-only HTTP/2 preflights; never claim MTProto parity."""

import argparse
import json
from pathlib import Path


def compare(left: dict, right: dict) -> dict:
    allowed = {"tls_alpn_head_only"}
    if left.get("scope") not in allowed or right.get("scope") not in allowed:
        raise ValueError("Only read-only TLS/ALPN preflights may be compared")
    if left.get("host") != right.get("host"):
        raise ValueError("Both preflights must target the exact same host")
    if left.get("probe") != "python-httpx-upstream-stack" or right.get("probe") != "android-okhttp-stack":
        raise ValueError("Unexpected preflight implementations")
    ok = bool(left.get("accepted") and right.get("accepted"))
    return {
        "host": left["host"],
        "scope": "tls_alpn_head_only",
        "httpx": {"status": left["http_status"], "http_version": left["http_version"], "elapsed_ms": left["elapsed_ms"]},
        "okhttp": {"status": right["http_status"], "http_version": right["http_version"], "elapsed_ms": right["elapsed_ms"]},
        "both_accepted": ok,
        "mtproto_data_plane_tested": False,
        "android_device_tested": False,
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--httpx", required=True)
    p.add_argument("--okhttp", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()
    left = json.loads(Path(args.httpx).read_text(encoding="utf-8"))
    right = json.loads(Path(args.okhttp).read_text(encoding="utf-8"))
    result = compare(left, right)
    Path(args.output).write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["both_accepted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
