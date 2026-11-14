"""Rendered-width audit of the shipped i18n bundles.

Answers one question per invocation: *for a given key, which languages render
wider than the rest, and by how much?* Widths come from the real advance
metrics of the fonts the game actually loads, not from a character-count proxy.

    python tools/i18n/width.py online.hub.tab.shadowRanked   # one key, per locale
    python tools/i18n/width.py --all                         # sweep, worst keys first
    python tools/i18n/width.py --all --limit 20              # top 20 only
    python tools/i18n/width.py menu.exit --json              # machine-readable

## Why rendered width

A character count is not a width. Roboto's Cyrillic and Greek sit at different
advances from its Latin; CJK glyphs are full-width. The same 10-character string
can differ ~2x across scripts, and English is routinely the *shortest* string
in the matrix, so ratios computed against it mislead. Everything here measures
ink.

## Font stack

Mirrors `ScriptProfile` (`render/fonts/ScriptProfile.kt`): zh/ja use
NotoSansSC as primary with Roboto filling Latin runs, every other locale uses
Roboto primary with NotoSansSC filling Han. Glyphs missing from the primary
are looked up in the fallback, and a codepoint in neither is reported as
`missing` rather than silently measured as zero.

## Outliers

Robust z-score over the locales for one key (Iglewicz-Hoaglin): `z = 0.6745 *
(w - median) / MAD`, flagged at |z| > `Z_THRESHOLD`. Median and MAD are used
because a single very long language would drag a mean and a standard deviation
enough to hide itself. Absolute size is meaningless for a short key (a 2-glyph
tile name can legitimately be 6x its source), so `--all` ranks by z and not by
raw width.

## Rich-text markup

Bundle values may carry markup (see `render/richtext/RichMarkupParser.kt`),
which occupies no ink. It is removed before measuring: `[hl]`-style role
tags drawn from `RichColorPalette.KNOWN_NAMES`, `[#RRGGBB]` hex tags, their
`[/]` closers, `**`/`*`/`__` emphasis, and `{…}` substitutions (MessageFormat
placeholders and inline tile notation), each counted and reported so a value
that is mostly substitution does not read as a short string.

Anything else in brackets is **kept** — the parser treats an unknown `[word]`
as literal text, and the bundles really do contain literal `[Settings]`,
`[Reference]`, `[Referenz]` and friends.

## Known approximations

- **Kerning is ignored.** Roboto's GPOS kerning moves a string by well under
  one glyph; it does not change any ranking and would only slow the sweep.
- **Shaping is not simulated.** No complex-script joining and no vertical
  metrics — horizontal advance sums only, which is what the chrome draws.
- **A `{…}` substitution measures as zero.** Its real width depends on the
  value it interpolates and so is not comparable across locales; the count is
  reported instead.
- Sizes are reported in em fractions, so the same number applies at any font
  size. Multiply by the design px of the face to get world pixels.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import statistics
import sys
from dataclasses import dataclass

try:
    from fontTools.ttLib import TTFont
except ImportError:  # pragma: no cover
    sys.exit("fontTools is required: pip install fonttools")

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
I18N_DIR = os.path.join(ROOT, "assets", "i18n")
FONT_DIR = os.path.join(ROOT, "assets", "fonts")

ROBOTO = os.path.join(FONT_DIR, "Roboto-Regular.ttf")
NOTO = os.path.join(FONT_DIR, "NotoSansSC-Regular.otf")

# `ScriptProfile.primaryIsCjk`: zh and ja lead with NotoSansSC.
CJK_PRIMARY = {"zh", "ja"}

Z_THRESHOLD = 3.5


# ── properties parsing ──────────────────────────────────────────────────────

def load_bundle(path: str) -> dict[str, str]:
    """Parse a .properties bundle, joining `\\`-continued lines."""
    out: dict[str, str] = {}
    key: str | None = None
    parts: list[str] = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\r\n")
            if key is not None:
                if line.endswith("\\"):
                    parts.append(line[:-1])
                    continue
                out[key] = "".join(parts) + line
                key, parts = None, []
                continue
            stripped = line.strip()
            if not stripped or stripped[0] in "#!":
                continue
            if "=" not in stripped:
                continue
            k, v = stripped.split("=", 1)
            if v.endswith("\\"):
                key, parts = k.strip(), [v[:-1]]
            else:
                out[k.strip()] = v
    return out


def load_bundles() -> dict[str, dict[str, str]]:
    """Every `ui*.properties` in [I18N_DIR], keyed by tag; `ui` becomes `en`."""
    bundles = {}
    for path in sorted(glob.glob(os.path.join(I18N_DIR, "ui*.properties"))):
        stem = os.path.basename(path)[: -len(".properties")]
        tag = stem[3:] if stem.startswith("ui_") else "en"
        bundles[tag] = load_bundle(path)
    return bundles


# ── font metrics ────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class Face:
    """Advance widths in em fractions, read once from the shipped font file."""
    advances: dict[int, float]
    upem: int

    @classmethod
    def load(cls, path: str) -> "Face":
        font = TTFont(path, lazy=True)
        upem = font["head"].unitsPerEm
        hmtx = font["hmtx"]
        advances = {cp: hmtx[glyph][0] / upem for cp, glyph in font.getBestCmap().items()}
        font.close()
        return cls(advances=advances, upem=upem)


def width_em(faces: list[Face], text: str) -> tuple[float, int]:
    """Rendered width of [text] in em, plus how many glyphs no face provided.

    Each codepoint takes the first face that has it, matching how `Fonts`
    chains primary then fallback.
    """
    total, missing = 0.0, 0
    for ch in text:
        cp = ord(ch)
        for face in faces:
            advance = face.advances.get(cp)
            if advance is not None:
                total += advance
                break
        else:
            missing += 1
    return total, missing


def faces_for(tag: str, roboto: Face, noto: Face) -> list[Face]:
    return [noto, roboto] if tag in CJK_PRIMARY else [roboto, noto]


# ── markup stripping ────────────────────────────────────────────────────────

# `RichColorPalette.KNOWN_NAMES` — the only `[word]` tags the parser treats as
# colour. Anything else in brackets is literal text and must survive.
KNOWN_COLORS = "|".join(
    "muted|dim|accent|hl|good|win|bad|danger|warn|info|cool|link|wind"
)
_RE_CLOSER = re.compile(r"\[/[A-Za-z0-9#]*\]")
_RE_COLOR = re.compile(rf"\[(?:#[0-9A-Fa-f]{{6,8}}|(?:{KNOWN_COLORS}))\]")
_RE_EMPHASIS = re.compile(r"\*\*|__|(?<!\*)\*(?!\*)")
_RE_SUBSTITUTION = re.compile(r"\{[^{}]*\}")


def strip_markup(text: str) -> tuple[str, int]:
    """Project a bundle value to the ink it actually paints.

    Returns the plain text plus how many `{…}` substitutions were dropped;
    see the module docstring for why those and only those are removed.
    """
    plain = _RE_CLOSER.sub("", text)
    plain = _RE_COLOR.sub("", plain)
    plain = _RE_EMPHASIS.sub("", plain)
    subs = len(_RE_SUBSTITUTION.findall(plain))
    return _RE_SUBSTITUTION.sub("", plain), subs


# ── outlier maths ───────────────────────────────────────────────────────────

def robust_z(values: list[float]) -> list[float]:
    """Iglewicz-Hoaglin z-scores; all-equal input yields all zeros."""
    if len(values) < 2:
        return [0.0] * len(values)
    med = statistics.median(values)
    mad = statistics.median([abs(v - med) for v in values])
    if mad == 0:
        return [0.0] * len(values)
    return [0.6745 * (v - med) / mad for v in values]


def audit(key: str, bundles: dict[str, dict[str, str]], roboto: Face,
          noto: Face) -> dict | None:
    """Measure [key] in every locale that ships it, and flag the outliers."""
    if key not in bundles.get("en", {}):
        return None
    rows = []
    for tag, bundle in sorted(bundles.items()):
        text = bundle.get(key)
        if not text:
            continue
        plain, subs = strip_markup(text)
        em, missing = width_em(faces_for(tag, roboto, noto), plain)
        rows.append({"locale": tag, "text": text, "em": em,
                     "missing": missing, "subs": subs})

    if not rows:
        return None
    widths = [r["em"] for r in rows]
    for row, z in zip(rows, robust_z(widths)):
        row["z"] = z
        row["outlier"] = abs(z) > Z_THRESHOLD

    longest = max(rows, key=lambda r: r["em"])
    shortest = min(rows, key=lambda r: r["em"])
    outliers = [r for r in rows if r["outlier"]]
    return {
        "key": key,
        "source": next((r for r in rows if r["locale"] == "en"), None),
        "rows": sorted(rows, key=lambda r: -r["em"]),
        "longest": longest,
        "shortest": shortest,
        "spread": longest["em"] / shortest["em"] if shortest["em"] else float("inf"),
        "outliers": outliers,
        "untranslated": [r["locale"] for r in rows if r["locale"] != "en"] and
                        [t for t in bundles if t != "en" and key not in bundles[t]],
    }


# ── reporting ───────────────────────────────────────────────────────────────

def render(key_report: dict) -> str:
    src = key_report["source"]
    lines = [key_report["key"]]
    if src:
        lines.append(f"  en  {src['em']:.3f} em  {src['text']}")
    lines.append(f"  {'locale':<6} {'em':>7} {'z':>6}  text")
    for row in key_report["rows"]:
        mark = " *" if row["outlier"] else "  "
        flags = ""
        if row["subs"]:
            flags += f"  [{row['subs']} subst]"
        if row["missing"]:
            flags += f"  [{row['missing']} missing glyph]"
        lines.append(f"  {row['locale']:<6} {row['em']:>7.3f} {row['z']:>6.1f}{mark} {row['text']}{flags}")
    longest, spread = key_report["longest"], key_report["spread"]
    lines.append(
        f"  widest: {longest['locale']} x{spread:.2f} vs {key_report['shortest']['locale']}"
    )
    if key_report["untranslated"]:
        lines.append(f"  untranslated in: {', '.join(key_report['untranslated'])}")
    return "\n".join(lines)


def main() -> int:
    # Bundles are UTF-8; a default Windows console is cp1251/cp1252 and would
    # raise mid-report. Reconfigure rather than require the caller to set it.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("key", nargs="?", help="bundle key to measure")
    ap.add_argument("--all", action="store_true", help="sweep every shared key")
    ap.add_argument("--limit", type=int, default=25, help="rows for --all (default 25)")
    ap.add_argument("--json", action="store_true", help="emit JSON instead of text")
    args = ap.parse_args()

    if not args.key and not args.all:
        ap.error("give a key, or --all")

    bundles = load_bundles()
    roboto, noto = Face.load(ROBOTO), Face.load(NOTO)

    if args.all:
        shared = [k for k in bundles["en"] if any(k in b for b in bundles.values())]
        reports = [r for r in (audit(k, bundles, roboto, noto) for k in shared) if r]
        # Rank by the strongest outlier; keys nobody flags land at 0.
        reports.sort(key=lambda r: -max((abs(x["z"]) for x in r["rows"]), default=0.0))
        reports = reports[: args.limit]
        if args.json:
            print(json.dumps(reports, ensure_ascii=False, indent=2))
        else:
            print(f"{len(shared)} shared keys, showing {len(reports)} worst outliers\n")
            for report in reports:
                print(render(report))
                print()
        return 0

    report = audit(args.key, bundles, roboto, noto)
    if report is None:
        print(f"{args.key}: not present in the source bundle", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else render(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())