#!/usr/bin/env python3
"""清理立绘 PNG 的白底预乘杂色，保留抗锯齿半透明边。"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

NAMES = (
    "default",
    "happy",
    "sleepy",
    "curious",
    "reading",
    "bored",
    "greeting",
    "away",
    "lost",
)

# 叠在白底上几乎不可见时才删除（保留正常抗锯齿边）
INVISIBLE_ON_WHITE = 254
# 仅丢弃极低 alpha 且几乎全白的像素
MIN_ALPHA_TO_KEEP = 6


def decontaminate_from_white(r: int, g: int, b: int, a: int) -> tuple[int, int, int, int]:
    if a <= 0:
        return 0, 0, 0, 0

    alpha = a / 255.0
    on_white_r = r * alpha + 255 * (1 - alpha)
    on_white_g = g * alpha + 255 * (1 - alpha)
    on_white_b = b * alpha + 255 * (1 - alpha)

    nearly_invisible = (
        on_white_r >= INVISIBLE_ON_WHITE
        and on_white_g >= INVISIBLE_ON_WHITE
        and on_white_b >= INVISIBLE_ON_WHITE
    )
    if nearly_invisible and a <= MIN_ALPHA_TO_KEEP:
        return 0, 0, 0, 0

    if a >= 255:
        return r, g, b, 255

    # 只修正 RGB，保留 alpha 以维持平滑边缘
    inv = 1.0 - alpha
    nr = int(max(0, min(255, (r - 255 * inv) / alpha)))
    ng = int(max(0, min(255, (g - 255 * inv) / alpha)))
    nb = int(max(0, min(255, (b - 255 * inv) / alpha)))
    return nr, ng, nb, a


def clean_png(path: Path) -> int:
    image = Image.open(path).convert("RGBA")
    pixels = image.load()
    width, height = image.size
    changed = 0

    for y in range(height):
        for x in range(width):
            old = pixels[x, y]
            new = decontaminate_from_white(*old)
            if new != old:
                pixels[x, y] = new
                changed += 1

    image.save(path, optimize=True)
    return changed


def main() -> int:
    repo = Path(__file__).resolve().parents[2]
    targets = [
        repo / "apps/web/public/images/illustrations",
        repo / "apps/web/public/images/_incoming",
    ]

    total_changed = 0
    for folder in targets:
        if not folder.is_dir():
            continue
        for name in NAMES:
            png = folder / f"{name}.png"
            if not png.exists():
                continue
            changed = clean_png(png)
            total_changed += changed
            print(f"{png.relative_to(repo)}: {changed} px updated")

    print(f"done, {total_changed} pixels updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
