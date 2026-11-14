"""Rank authored Kotlin files by on-disk size, split into test and non-test.

A file is a test when the source set directly under ``src`` is a test source
set (its name is ``test``, starts with ``test``, or ends with ``Test``), or
when the file name ends with ``Test.kt`` or ``Tests.kt``. Directories named
``build``, ``.gradle``, ``.git``, ``out``, ``.idea``, and ``node_modules`` are
not walked.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

SKIP_DIRS = {"build", ".gradle", ".git", "out", ".idea", "node_modules"}


def source_set_is_test(name: str) -> bool:
    lowered = name.lower()
    return lowered == "test" or lowered.startswith("test") or lowered.endswith("test")


def is_test(path: Path) -> bool:
    parts = path.parts
    last = len(parts) - 1
    for index, part in enumerate(parts):
        if part.lower() != "src":
            continue
        source_set_index = index + 1
        if source_set_index < last and source_set_is_test(parts[source_set_index]):
            return True
    name = path.name.lower()
    return name.endswith("test.kt") or name.endswith("tests.kt")


def measure(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    lines = data.count(b"\n")
    if data and not data.endswith(b"\n"):
        lines += 1
    return len(data), lines


def collect(root: Path) -> tuple[list[tuple[int, int, str]], list[tuple[int, int, str]]]:
    sources: list[tuple[int, int, str]] = []
    tests: list[tuple[int, int, str]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if name not in SKIP_DIRS]
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            path = Path(dirpath) / filename
            size, lines = measure(path)
            row = (size, lines, path.relative_to(root).as_posix())
            if is_test(path):
                tests.append(row)
            else:
                sources.append(row)
    sources.sort(key=lambda row: (-row[0], row[2]))
    tests.sort(key=lambda row: (-row[0], row[2]))
    return sources, tests


def print_summary(label: str, rows: list[tuple[int, int, str]]) -> None:
    total_bytes = sum(row[0] for row in rows)
    total_lines = sum(row[1] for row in rows)
    print(f"{label}: {len(rows)} files, {total_bytes:,} bytes, {total_lines:,} lines")


def print_table(title: str, rows: list[tuple[int, int, str]], top: int) -> None:
    shown = rows[:top]
    total_bytes = sum(row[0] for row in rows) or 1
    print()
    print(f"# {title} (top {min(top, len(rows))} of {len(rows)} by bytes)")
    if not shown:
        print("(none)")
        return
    size_width = max(len(f"{row[0]:,}") for row in shown)
    line_width = max(len(f"{row[1]:,}") for row in shown)
    print(f"{'bytes':>{size_width}}  {'share':>6}  {'lines':>{line_width}}  path")
    for size, lines, rel in shown:
        share = 100.0 * size / total_bytes
        print(f"{size:>{size_width},}  {share:5.1f}%  {lines:>{line_width},}  {rel}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="Tree to scan")
    parser.add_argument("--top", type=int, default=20, help="Rows to print per group")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--sources", action="store_true", help="Print the non-test ranking only")
    group.add_argument("--tests", action="store_true", help="Print the test ranking only")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 1
    if args.top < 1:
        print("--top must be at least 1", file=sys.stderr)
        return 1

    sources, tests = collect(root)
    print(f"root: {root}")
    print_summary("non-test", sources)
    print_summary("test", tests)
    if not args.tests:
        print_table("non-test", sources, args.top)
    if not args.sources:
        print_table("test", tests, args.top)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
