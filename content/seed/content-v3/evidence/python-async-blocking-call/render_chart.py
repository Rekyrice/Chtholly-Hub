from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
RESULTS = HERE / "results.json"
OUTPUT = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "python-async-blocking-call-chart.png"


def font(size: int, bold: bool = False):
    candidates = [
        Path("C:/Windows/Fonts/seguisb.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf"),
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


data = json.loads(RESULTS.read_text(encoding="utf-8"))
summary = data["summary"]
rows = [
    ("Direct blocking", summary["direct_blocking"], "#f97373"),
    ("Awaitable sleep", summary["cooperative_wait"], "#65a7ff"),
    ("Thread offload", summary["thread_offload"], "#56d6a0"),
]

canvas = Image.new("RGB", (1400, 820), "#0e1524")
draw = ImageDraw.Draw(canvas)
draw.text((80, 62), "50 concurrent tasks, 50 ms each", font=font(48, True), fill="#f5f7fb")
draw.text(
    (80, 124),
    f"Python {data['python']} · median of {data['rounds']} rounds · lower is better",
    font=font(25),
    fill="#aeb8ca",
)

maximum = max(row[1]["medianSeconds"] for row in rows)
for index, (label, values, color) in enumerate(rows):
    top = 222 + index * 168
    median = values["medianSeconds"]
    width = max(8, int(980 * median / maximum))
    draw.text((80, top), label, font=font(30, True), fill="#f5f7fb")
    draw.rounded_rectangle((80, top + 52, 1080, top + 102), radius=18, fill="#202b3e")
    draw.rounded_rectangle((80, top + 52, 80 + width, top + 102), radius=18, fill=color)
    draw.text((1110, top + 52), f"{median:.3f}s", font=font(30, True), fill=color)
    draw.text(
        (1110, top + 91),
        f"{values['speedupVsDirect']:.1f}x",
        font=font(20),
        fill="#aeb8ca",
    )

draw.text(
    (80, 742),
    "Synthetic asyncio timing experiment — not an HTTP framework benchmark",
    font=font(22),
    fill="#7f8ba0",
)
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
canvas.save(OUTPUT)
print(OUTPUT)
