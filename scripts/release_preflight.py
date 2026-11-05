#!/usr/bin/env python3
"""Offline-check the selected GitHub release tag against Android version metadata."""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import sys

TAG = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?\Z")


def gradle_value(text: str, key: str) -> str:
    values = re.findall(rf"(?m)^\s*{re.escape(key)}\s*=\s*(?:\"([^\"]+)\"|(\d+))\s*$", text)
    if len(values) != 1:
        raise ValueError(f"Expected one {key} declaration in app/build.gradle.kts, found {len(values)}")
    return values[0][0] or values[0][1]


def validate(tag: str, gradle_text: str, event_name: str, ref_type: str, ref_name: str) -> dict:
    if TAG.fullmatch(tag) is None:
        raise ValueError('Release tag must be vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-suffix')
    version_name = gradle_value(gradle_text, 'versionName')
    version_code = int(gradle_value(gradle_text, 'versionCode'))
    if version_code <= 0:
        raise ValueError('versionCode must be positive')
    if tag[1:] != version_name:
        raise ValueError(f'Release tag {tag} does not match versionName {version_name}')
    if event_name == 'workflow_dispatch':
        if (ref_type, ref_name) != ('branch', 'main'):
            raise ValueError('Manual release must be dispatched from main')
    elif event_name == 'push':
        if (ref_type, ref_name) != ('tag', tag):
            raise ValueError('Tag push must build the exact tag commit')
    else:
        raise ValueError(f'Unsupported release event: {event_name}')
    return {'versionName':version_name,'versionCode':version_code,'tag':tag}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--gradle-file', default='app/build.gradle.kts')
    p.add_argument('--tag', required=True)
    p.add_argument('--event-name', required=True)
    p.add_argument('--ref-type', required=True)
    p.add_argument('--ref-name', required=True)
    args = p.parse_args(argv)
    try:
        result = validate(args.tag, Path(args.gradle_file).read_text(encoding='utf-8'),
                          args.event_name, args.ref_type, args.ref_name)
    except (ValueError, OSError) as ex:
        print(f'Release preflight failed: {ex}', file=sys.stderr)
        return 1
    print(f"Verified {result['tag']} (versionCode {result['versionCode']}); ref and Gradle metadata agree")
    return 0


if __name__ == '__main__':
    sys.exit(main())
