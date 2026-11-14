---
name: kotlin-file-sizes
description: >
  Rank authored Kotlin files by on-disk size and split test sources from
  production sources. Use when the user asks for the biggest .kt files,
  Kotlin file-size offenders, test versus production source size, or runs
  /kotlin-file-sizes.
---

# Kotlin file sizes

Run `scripts/kt-sizes.py`. Do not walk the tree yourself.

From the repository root:

```
py -3 .grok/skills/kotlin-file-sizes/scripts/kt-sizes.py
```

Flags:

- `--sources` — non-test ranking only
- `--tests` — test ranking only
- `--top N` — rows per group (default 20)
- `--root PATH` — tree to scan (default: the current directory)

The script owns classification and the skip list. It always prints totals for both groups. Rank is by file size in bytes. `lines` and `share` (percent of that group's bytes) are context.

Pass `--sources` when the user asks for production files or non-test offenders. Pass `--tests` when they ask for test files. Omit both flags when they want both groups. Report the script's table unchanged.
