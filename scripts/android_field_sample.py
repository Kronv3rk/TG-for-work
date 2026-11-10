#!/usr/bin/env python3
"""Read-only Android process, battery and memory sampling via adb.

Does not install apps, toggle radios, unlock screens, capture logcat, or read
Telegram messages. Run during a manually arranged real-device proxy session.
"""

import argparse
import csv
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess
import time

DEFAULT_PACKAGE = "com.flowseal.tgwsproxy"
PACKAGE_RE = re.compile(r"^[a-zA-Z_][\w]*(?:\.[a-zA-Z_][\w]*)+$")


def parse_pss_kb(output: str):
    match = re.search(r"(?m)^\s*TOTAL PSS:\s*([\d,]+)\b", output)
    if match:
        return int(match.group(1).replace(",", ""))
    # Older Android APIs often have TOTAL at the bottom with the PSS first.
    match = re.search(r"(?m)^\s*TOTAL\s+([\d,]+)\s+", output)
    return int(match.group(1).replace(",", "")) if match else None


def parse_battery(output: str):
    values = {}
    for label, key in [("level", "level_percent"), ("temperature", "temperature_tenths_c")]:
        match = re.search(r"(?m)^\s*" + label + r":\s*(-?\d+)\s*$", output)
        values[key] = int(match.group(1)) if match else None
    return values


def adb_call(serial, *args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["shell", *args]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"adb is unavailable or device command timed out: {exc}") from exc
    if result.returncode:
        raise RuntimeError(f"adb command failed (exit {result.returncode}); check device connection")
    return result.stdout


def sample(serial, package):
    pid = adb_call(serial, "pidof", "-s", package).strip()
    battery = parse_battery(adb_call(serial, "dumpsys", "battery"))
    # Only package-level aggregate PSS, never individual allocation contents.
    meminfo = adb_call(serial, "dumpsys", "meminfo", package)
    return {
        "utc_timestamp": datetime.now(timezone.utc).isoformat(),
        "app_running": int(bool(pid)),
        "pss_kb": parse_pss_kb(meminfo),
        **battery,
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", help="Optional adb device serial, never included in output")
    p.add_argument("--package", default=DEFAULT_PACKAGE)
    p.add_argument("--minutes", type=float, default=30, help="Capture duration, 0 < minutes <= 240")
    p.add_argument("--interval", type=float, default=15, help="Sampling period, seconds >= 5")
    p.add_argument("--output", required=True, help="CSV output path on this computer")
    args = p.parse_args()
    if not PACKAGE_RE.fullmatch(args.package) or not 0 < args.minutes <= 240 or args.interval < 5:
        p.error("Provide a valid Android package, duration 0–240 min, and interval >= 5 s")
    filename = Path(args.output)
    filename.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.minutes * 60
    fields = ["utc_timestamp", "app_running", "pss_kb", "level_percent", "temperature_tenths_c"]
    with filename.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        while True:
            writer.writerow(sample(args.serial, args.package))
            f.flush()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(args.interval, remaining))
    print(f"Saved {filename}; collected process-level metrics only (no messages or network payloads)")


if __name__ == "__main__":
    main()
