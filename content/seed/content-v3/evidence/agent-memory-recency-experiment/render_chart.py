from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
OUTPUT = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "agent-memory-recency-chart.png"


def font(size: int, bold: bool = False):
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


data = json.loads((HERE / "results.json").read_text(encoding="utf-8"))
summary = data["summary"]
total = data["queryCount"]
rows = [
    ("只看词面相似度", summary["baselineCorrect"], summary["baselineIncorrect"], "#f2778d"),
    ("先消解新旧关系", summary["recencyAwareCorrect"], summary["recencyAwareIncorrect"], "#53d7a1"),
]

canvas = Image.new("RGB", (1400, 820), "#0e1524")
draw = ImageDraw.Draw(canvas)
draw.text((80, 66), "Agent 记忆检索：旧值冲突实验", font=font(48, True), fill="#f5f7fb")
draw.text(
    (80, 132),
    f"{data['memoryCount']} 条记忆 · {total} 条冻结查询 · 确定性词面代理",
    font=font(25),
    fill="#aeb8ca",
)

for index, (label, correct, incorrect, color) in enumerate(rows):
    top = 260 + index * 220
    draw.text((80, top), label, font=font(34, True), fill="#f5f7fb")
    draw.rounded_rectangle((80, top + 70, 1120, top + 132), radius=20, fill="#202b3e")
    width = int(1040 * correct / total)
    draw.rounded_rectangle((80, top + 70, 80 + width, top + 132), radius=20, fill=color)
    draw.text((1160, top + 68), f"{correct} / {total}", font=font(32, True), fill=color)
    draw.text((80, top + 150), f"错误 {incorrect} 次", font=font(22), fill="#aeb8ca")

draw.text(
    (80, 742),
    "只验证 supersedes 冲突消解顺序，不代表真实 LLM 或 embedding 能力",
    font=font(22),
    fill="#7f8ba0",
)
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
canvas.save(OUTPUT)
print(OUTPUT)
