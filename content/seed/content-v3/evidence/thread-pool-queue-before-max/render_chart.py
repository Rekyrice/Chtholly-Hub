#!/usr/bin/env python3
"""Render the tracked ThreadPoolExecutor snapshot as a compact PNG chart."""

import json
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont


def font(size, bold=False):
    candidates = [
        pathlib.Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        pathlib.Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


results = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
output = pathlib.Path(sys.argv[2])
image = Image.new("RGB", (1400, 820), "#111827")
draw = ImageDraw.Draw(image)
draw.text((80, 60), "maxPoolSize = 64，线程为什么没有增长？", fill="#F9FAFB", font=font(42, True))
draw.text((80, 120), "corePoolSize = 4 · 同时提交 40 个被闩锁阻塞的任务", fill="#9CA3AF", font=font(24))

colors = {"poolSize": "#60A5FA", "queued": "#F59E0B", "rejected": "#F87171"}
labels = {"poolSize": "线程数", "queued": "队列中", "rejected": "拒绝"}
for column, snapshot in enumerate(results["snapshots"]):
    x = 90 + column * 650
    title = "无界 LinkedBlockingQueue" if column == 0 else "有界 ArrayBlockingQueue(8)"
    draw.rounded_rectangle((x, 190, x + 570, 700), radius=24, fill="#1F2937")
    draw.text((x + 35, 225), title, fill="#F9FAFB", font=font(29, True))
    for row, key in enumerate(("poolSize", "queued", "rejected")):
        value = snapshot[key]
        y = 320 + row * 110
        draw.text((x + 35, y), labels[key], fill="#D1D5DB", font=font(24))
        draw.rounded_rectangle((x + 165, y, x + 515, y + 42), radius=12, fill="#374151")
        width = 0 if value == 0 else max(12, int(350 * value / 40))
        draw.rounded_rectangle((x + 165, y, x + 165 + width, y + 42), radius=12, fill=colors[key])
        draw.text((x + 525, y - 2), str(value), fill=colors[key], font=font(28, True), anchor="ra")
    note = "任务先进入无界队列，maximumPoolSize 没有机会生效" if column == 0 else "队列填满后，线程才从 corePoolSize 继续增长"
    draw.text((x + 35, 650), note, fill="#D1D5DB", font=font(19))

output.parent.mkdir(parents=True, exist_ok=True)
image.save(output, "PNG", optimize=True)
