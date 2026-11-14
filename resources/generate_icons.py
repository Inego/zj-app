#!/usr/bin/env python3
"""Regenerate the whole application icon set from one mahjong-tile render.

The render is the only hand-made input. Everything the three targets ship -
Desktop (lwjgl3), Wasm (teavm) and Android - is derived here, so a new render
means re-running this script and nothing else.

    py -3 resources/generate_icons.py --source <render.png>

Writes `resources/icon-source.png` (the 1024 master), the Desktop `.png` /
`.ico` / `.icns`, the Wasm `favicon.ico`, and the whole Android set including
`android/ic_launcher-playstore.png` for the store listing.

Design notes
------------
* **Framing is per target, not one crop for everything.** A launcher, a taskbar
  and a 16px favicon have different masks and different amounts of room, so
  each target gets the tile sized for the space it actually has
  (`*_FILL_H` below). The tile aspect is never distorted.
* **Alpha is premultiplied while resampling.** The tile is bright ivory next to
  a near-black glyph; a straight RGBA Lanczos blends the ivory into the glyph
  edge and leaves a pale halo at small sizes. Premultiplying first avoids it.
* **The adaptive-icon foreground is sized to the 66dp guaranteed-safe circle**,
  not to the 108dp canvas, so no third-party launcher mask can clip the glyph.
* The legacy `ic_launcher*.webp` plates are pre-API-26 only (minSdk is 28, so
  `mipmap-anydpi-v26` always wins). They are still rebuilt so the tree never
  carries a stale brand; their felt board is reconstructed from the parameters
  measured off the previous set.

Requires Pillow with WebP support; run with the system Python (`py -3`).
"""

from __future__ import annotations

import argparse
import io
import struct
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageMath

REPO = Path(__file__).resolve().parent.parent

# --- Framing -------------------------------------------------------------
# Fraction of the icon canvas the tile's height should occupy.
FILL_DESKTOP = 0.879      # taskbar / dock / .ico / .icns: no mask, fill it
FILL_FAVICON = 0.879      # same art as desktop, just smaller
FILL_ADAPTIVE = 0.480     # 66dp guaranteed-safe circle inside the 108dp canvas
FILL_SPLASH = 0.511       # matches the measured previous splash framing
FILL_PLATE = 0.762        # tile height as a fraction of the legacy board size
FILL_PLAYSTORE = 0.781    # listing art sits edge to edge, so the tile is larger

# --- Legacy felt board (measured off the previous icon set) --------------
BOARD_RGB = (61, 220, 132)
GRID_RGB = (97, 227, 155)
GRID_STEP = 21 / 152      # grid pitch, relative to board size
GRID_INSET = 2 / 152      # grid inset from the board edge
GRID_WIDTH = 1 / 152      # grid line width
CORNER_RADIUS = 11 / 152  # rounded-square plate radius
# Transparent margin between the canvas edge and the plate, per shape. The
# round plate sat closer to the edge, so the tile reads larger on a round mask.
PLATE_INSET = {"square": 20 / 192, "round": 8 / 192, "full": 0.0}
BOARD_SHADOW_BLUR = 3 / 152
BOARD_SHADOW_DROP = 2 / 152
BOARD_SHADOW_ALPHA = 0.32
TILE_SHADOW_BLUR = 2 / 152
TILE_SHADOW_DROP = 2 / 152
TILE_SHADOW_ALPHA = 0.30

# --- Densities -----------------------------------------------------------
# name -> (legacy square px, legacy round px, adaptive foreground px)
DENSITIES = {
    "mdpi": (48, 48, 108),
    "hdpi": (72, 72, 162),
    "xhdpi": (96, 96, 216),
    "xxhdpi": (144, 144, 324),
    "xxxhdpi": (192, 192, 432),
}

SPLASH_PX = 960
MASTER_PX = 1024
PLAYSTORE_PX = 512

ICO_SIZES = (16, 32, 48, 64, 128, 256)
# Window/taskbar icon, loaded at runtime by Lwjgl3Launcher.setWindowIcon.
# These are separate files from the packaged .ico: jpackage bakes logo.ico into
# the executable, while these are what the window itself displays.
WINDOW_ICON_SIZES = (16, 32, 64, 128)
FAVICON_SIZES = (16, 32, 48)
ICNS_ENTRIES = (("ic07", 128), ("ic08", 256), ("ic09", 512), ("ic10", 1024))

ALPHA_CROP = 4      # ignore the near-invisible fringe when cropping the render
GLYPH_LUM_LO = 128  # themed-icon silhouette ramp
GLYPH_LUM_HI = 168
GLYPH_MIN_AREA = 0.0004   # drop antialiasing specks when picking glyph blobs


# --- resampling ----------------------------------------------------------

def resize_pm(im: Image.Image, size) -> Image.Image:
    """Resize RGBA with the colour channels premultiplied by alpha."""
    size = (size, size) if isinstance(size, (int, float)) else tuple(size)
    # int(), not round(): PIL truncates a float size silently, which turns a
    # precedence slip in a caller into a subtly wrong image rather than an error.
    size = tuple(int(v) for v in size)
    if min(size) < 1:
        raise ValueError(f"resize target must be >= 1px, got {size}")
    r, g, b, a = im.convert("RGBA").split()
    pm = Image.merge("RGB", (ImageChops.multiply(r, a),
                             ImageChops.multiply(g, a),
                             ImageChops.multiply(b, a)))
    pm = pm.resize(size, Image.LANCZOS)
    a = a.resize(size, Image.LANCZOS)
    # Guard the divide: fully transparent pixels carry no colour to recover.
    inv = a.point(lambda v: 255.0 / v if v else 0.0)
    out = [ImageMath.lambda_eval(lambda a: a["C"] * a["I"],
                                C=c.convert("F"), I=inv).convert("L")
           for c in pm.split()]
    return Image.merge("RGBA", (*out, a))


def alpha_bbox(im: Image.Image, threshold: int = ALPHA_CROP):
    return im.split()[3].point(lambda v: 255 if v > threshold else 0).getbbox()


def fit(im: Image.Image, box: int) -> Image.Image:
    """Scale to fit a square box, preserving aspect. Never upscales."""
    w, h = im.size
    k = min(box / w, box / h, 1.0)
    return resize_pm(im, (max(1, round(w * k)), max(1, round(h * k))))


# --- tile ----------------------------------------------------------------

def load_tile(path: Path) -> Image.Image:
    """The render, cropped to the tile silhouette."""
    im = Image.open(path).convert("RGBA")
    box = alpha_bbox(im)
    if box is None:
        raise SystemExit(f"{path}: render is fully transparent")
    return im.crop(box)


def place(tile: Image.Image, canvas: int, fill_h: float) -> Image.Image:
    """Centre the tile in a square canvas at `fill_h` of the canvas height."""
    w, h = tile.size
    th = max(1, round(canvas * fill_h))
    tw = max(1, round(th * w / h))
    scaled = resize_pm(tile, (tw, th))
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(scaled, ((canvas - tw) // 2, (canvas - th) // 2))
    return out


# --- container formats ---------------------------------------------------

def make_ico(images: dict[int, Image.Image]) -> bytes:
    """Multi-size .ico, PNG-compressed entries (Vista+ and every browser)."""
    payloads = []
    for size in sorted(images):
        buf = io.BytesIO()
        images[size].save(buf, "PNG", optimize=True)
        payloads.append((size, buf.getvalue()))

    header = struct.pack("<HHH", 0, 1, len(payloads))
    entries, blobs, offset = b"", b"", 6 + 16 * len(payloads)
    for size, blob in payloads:
        entries += struct.pack("<BBBBHHII", size & 0xFF, size & 0xFF,
                               0, 0, 1, 32, len(blob), offset)
        offset += len(blob)
        blobs += blob
    return header + entries + blobs


def make_icns(images: dict[str, Image.Image]) -> bytes:
    """macOS .icns; every modern icon type takes a PNG payload."""
    chunks = []
    for kind, im in images.items():
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        blob = buf.getvalue()
        chunks.append(kind.encode("ascii") + struct.pack(">I", len(blob) + 8) + blob)
    body = b"".join(chunks)
    return b"icns" + struct.pack(">I", len(body) + 8) + body


# --- legacy felt board ---------------------------------------------------

def _board_mask(size: int, shape: str) -> Image.Image:
    """The plate silhouette at a given resolution, honouring the margin."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "full":
        # Play Store listing art: no margin and no rounded corners, because the
        # store applies its own mask and a transparent edge would read as a
        # seam against the listing background.
        draw.rectangle([0, 0, size - 1, size - 1], fill=255)
        return mask
    pad = PLATE_INSET[shape] * size
    box = [pad, pad, size - 1 - pad, size - 1 - pad]
    if shape == "square":
        draw.rounded_rectangle(box, radius=CORNER_RADIUS * size, fill=255)
    else:
        draw.ellipse(box, fill=255)
    return mask


def _drop_shadow(silhouette: Image.Image, size: int, blur: float,
                 drop: float, alpha: float) -> Image.Image:
    """A soft black shadow of `silhouette`, offset down, on a transparent canvas."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.paste((0, 0, 0, int(255 * alpha)), (0, 0), silhouette)
    layer = layer.filter(ImageFilter.GaussianBlur(blur * size))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(layer, (0, round(drop * size)))
    return out


def make_plate(size: int, shape: str, tile: Image.Image, ss: int = 4) -> Image.Image:
    """A felt board with a grid, the tile on top, both with soft drop shadows."""
    big = size * ss

    # Draw the felt and its grid at `ss` times the target size, then mask and
    # downsample in one step. alpha_composite silently crops when the source is
    # larger than the destination, so a supersampled layer must never reach it.
    board = Image.new("RGBA", (big, big), BOARD_RGB + (255,))
    grid = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(grid)
    inset = GRID_INSET * big
    step = GRID_STEP * big
    half = GRID_WIDTH * big / 2
    pos = inset
    while pos <= big - inset + half:
        draw.rectangle([pos - half, inset, pos + half, big - inset],
                       fill=GRID_RGB + (255,))
        draw.rectangle([inset, pos - half, big - inset, pos + half],
                       fill=GRID_RGB + (255,))
        pos += step
    board.alpha_composite(grid)

    board.putalpha(_board_mask(big, shape))
    board = resize_pm(board, (size, size))

    # The tile is sized against the plate, not the canvas, so the same source
    # art sits identically on both plate shapes.
    plate_px = round(size * (1 - 2 * PLATE_INSET[shape]))
    fill = FILL_PLAYSTORE if shape == "full" else FILL_PLATE
    # Pick the fill BEFORE rounding. Written as
    # `round(plate_px * A if cond else plate_px * B)`, the conditional binds
    # looser than `round`, so it evaluates as `round(plate_px * A if cond else
    # plate_px) * B` - a float, which then silently drops the tile in
    # alpha_composite. Keep the parens.
    th = max(1, round(plate_px * fill))
    tw = max(1, round(th * tile.width / tile.height))
    tile_im = resize_pm(tile, (tw, th))

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # The full-bleed plate has no transparent margin, so its own shadow would
    # only darken the felt; the tile's shadow still grounds it on the board.
    if shape != "full":
        out.alpha_composite(_drop_shadow(_board_mask(size, shape), size,
                                         BOARD_SHADOW_BLUR, BOARD_SHADOW_DROP,
                                         BOARD_SHADOW_ALPHA))
    out.alpha_composite(_drop_shadow(tile_im.split()[3], size,
                                     TILE_SHADOW_BLUR, TILE_SHADOW_DROP,
                                     TILE_SHADOW_ALPHA))
    out.alpha_composite(board)
    out.alpha_composite(tile_im, ((size - tw) // 2, (size - th) // 2))
    return out


# --- themed (monochrome) glyph -------------------------------------------

def glyph_mask(tile: Image.Image) -> Image.Image:
    """Silhouette of the engraved glyph, as a hard 8-bit mask.

    The glyph is only meaningful on the bright ivory face. Two things are not
    glyph and must be excluded or the themed icon grows stray marks:

    * the cobalt spine, and the dark rim shading where the tile turns away from
      the camera - so the search is inset from the tile silhouette;
    * the soft contact shadow under the tile.

    Insetting by a fixed fraction of the tile width keeps this stable across
    renders, unlike an absolute pixel inset that a larger render would defeat.
    """
    work = fit(tile, 512)
    w, h = work.size
    inset = round(w * 0.10)
    px = work.load()
    mask = Image.new("L", (w, h), 0)
    mp = mask.load()
    for y in range(inset, h - inset):
        for x in range(inset, w - inset):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            if b > r + 25 and b > 100:      # the cobalt spine is not the glyph
                continue
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if lum < GLYPH_LUM_LO:
                mp[x, y] = 255
            elif lum < GLYPH_LUM_HI:
                mp[x, y] = round(255 * (GLYPH_LUM_HI - lum) / (GLYPH_LUM_HI - GLYPH_LUM_LO))
    return mask.point(lambda v: 255 if v > 128 else 0)


def keep_blobs(mask: Image.Image) -> Image.Image:
    """Drop connected components below a noise floor (render antialiasing)."""
    w, h = mask.size
    px = mask.load()
    seen = bytearray(w * h)
    min_area = GLYPH_MIN_AREA * w * h
    out = Image.new("L", (w, h), 0)
    op = out.load()
    for y0 in range(h):
        for x0 in range(w):
            if not px[x0, y0] or seen[y0 * w + x0]:
                continue
            stack = [(x0, y0)]
            seen[y0 * w + x0] = 1
            blob = []
            while stack:
                x, y = stack.pop()
                blob.append((x, y))
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if 0 <= nx < w and 0 <= ny < h and not seen[ny * w + nx] and px[nx, ny]:
                        seen[ny * w + nx] = 1
                        stack.append((nx, ny))
            if len(blob) >= min_area:
                for x, y in blob:
                    op[x, y] = 255
    return out


def make_monochrome(glyph: Image.Image, canvas: int, fill_h: float) -> Image.Image:
    """Solid black glyph on transparency, sized like the adaptive foreground.

    The shape is carried entirely in the alpha channel: Android tints the
    monochrome layer with the caller's palette, so any colour here is discarded
    and a lossy WebP would only add colour noise around the edges.
    """
    glyph = glyph.crop(glyph.getbbox())
    solid = Image.new("RGBA", glyph.size, (0, 0, 0, 0))
    solid.putalpha(glyph)
    return place(solid, canvas, fill_h)


# --- writers -------------------------------------------------------------

def write_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG", optimize=True)
    print(f"  {path.relative_to(REPO)}  {im.width}x{im.height}")


def write_webp(im: Image.Image, path: Path, lossless: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if lossless:
        im.save(path, "WEBP", lossless=True, method=6, quality=100)
    else:
        im.save(path, "WEBP", quality=95, method=6)
    print(f"  {path.relative_to(REPO)}  {im.width}x{im.height}")


def write_bytes(data: bytes, path: Path, note: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print(f"  {path.relative_to(REPO)}  {note}")


# --- main ----------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, type=Path,
                    help="the rendered tile this icon set is built from")
    args = ap.parse_args()

    src: Path = args.source.resolve()
    if not src.is_file():
        raise SystemExit(f"source render not found: {src}")

    tile = load_tile(src)
    print(f"source : {src}")
    print(f"tile   : {tile.width}x{tile.height} (aspect {tile.width / tile.height:.4f})")

    # Master: desktop framing at full icns resolution, so the .icns is a copy
    # rather than an upscale of an already-upscaled master.
    master = place(tile, MASTER_PX, FILL_DESKTOP)
    write_png(master, REPO / "resources" / "icon-source.png")

    # --- Desktop ---------------------------------------------------------
    print("desktop")
    desk_dir = REPO / "lwjgl3" / "icons"
    write_png(place(tile, 256, FILL_DESKTOP), desk_dir / "logo.png")
    write_bytes(make_ico({s: place(tile, s, FILL_DESKTOP) for s in ICO_SIZES}),
                desk_dir / "logo.ico", f"{len(ICO_SIZES)} sizes")
    write_bytes(make_icns({kind: place(tile, size, FILL_DESKTOP)
                           for kind, size in ICNS_ENTRIES}),
                desk_dir / "logo.icns", ", ".join(k for k, _ in ICNS_ENTRIES))

    # The window/taskbar icon, loaded at runtime via setWindowIcon. Missing
    # these leaves the running app showing the old art even though the
    # packaged executable carries the new one.
    win_dir = REPO / "lwjgl3" / "src" / "main" / "resources"
    for size in WINDOW_ICON_SIZES:
        write_png(place(tile, size, FILL_DESKTOP), win_dir / f"icon{size}.png")

    # --- Wasm ------------------------------------------------------------
    print("wasm")
    write_bytes(make_ico({s: place(tile, s, FILL_FAVICON) for s in FAVICON_SIZES}),
                REPO / "teavm" / "webapp" / "favicon.ico", f"{len(FAVICON_SIZES)} sizes")

    # --- Android ---------------------------------------------------------
    print("android")
    res = REPO / "android" / "res"
    write_png(place(tile, SPLASH_PX, FILL_SPLASH),
              res / "drawable-nodpi" / "ic_splash.png")

    glyph = keep_blobs(glyph_mask(tile))
    box = glyph.getbbox()
    if box is None:
        raise SystemExit("no glyph found in the render; cannot build the themed icon")
    print(f"glyph  : {box[2]-box[0]}x{box[3]-box[1]} in 512px tile space")

    for density, (square_px, round_px, adaptive_px) in DENSITIES.items():
        mip = res / f"mipmap-{density}"
        write_webp(place(tile, adaptive_px, FILL_ADAPTIVE),
                   mip / "ic_launcher_foreground.webp")
        write_webp(make_monochrome(glyph, adaptive_px, FILL_ADAPTIVE),
                   mip / "ic_launcher_monochrome.webp", lossless=True)
        write_webp(make_plate(square_px, "square", tile), mip / "ic_launcher.webp")
        write_webp(make_plate(round_px, "round", tile), mip / "ic_launcher_round.webp")

    # Store listing art: 512px, full bleed, not a resource so it is never
    # packaged - it is uploaded to the store console by hand.
    write_png(make_plate(PLAYSTORE_PX, "full", tile),
              REPO / "android" / "ic_launcher-playstore.png")

    return 0


if __name__ == "__main__":
    sys.exit(main())
