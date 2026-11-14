#!/usr/bin/env python3
"""Reject byte-identical Roborazzi references within a module (standard library only)."""

import argparse
from collections import defaultdict
import hashlib
from pathlib import Path
import subprocess


# Exact groups of repository-relative paths, each with a justification. No exceptions today.
# Adding another copy to an allowed group must still fail; stale exceptions fail too.
DUPLICATE_EXCEPTIONS: dict[frozenset[str], str] = {}


def check(root: Path) -> int:
    paths = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root,
    ).decode().split("\0")
    groups = defaultdict(list)
    count = 0
    for name in sorted(set(paths)):
        module, marker, _ = name.partition("/src/test/screenshots/")
        path = root / name
        if not marker or not name.endswith(".png") or not path.is_file():
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        groups[module, digest].append(name)
        count += 1

    seen_exceptions = set()
    failures = 0
    for (module, digest), names in sorted(groups.items()):
        if len(names) < 2:
            continue
        group = frozenset(names)
        if DUPLICATE_EXCEPTIONS.get(group, "").strip():
            seen_exceptions.add(group)
            continue
        failures += 1
        print(f"Duplicate Roborazzi references in {module} (SHA-256 {digest}):")
        for name in names:
            print(f"  {name}")
    for group in DUPLICATE_EXCEPTIONS.keys() - seen_exceptions:
        failures += 1
        print(f"Stale or unjustified duplicate exception: {sorted(group)}")
    if count == 0:
        print("No Roborazzi references found.")
        return 1
    print(f"Roborazzi: {count} references, {failures} duplicate/exception errors.")
    return int(failures > 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    raise SystemExit(check(args.root.resolve()))
