from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
OUTPUT = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "rrf-hybrid-retrieval-ranking-chart.png"
COVER = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "rrf-hybrid-retrieval-ranking-cover.png"
CODE = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "rrf-hybrid-retrieval-ranking-code.png"


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
summary = data["strategies"]
rows = [
    ("词面候选", summary["lexical"], "#6aa9ff"),
    ("语义代理候选", summary["semanticProxy"], "#ee9a68"),
    ("RRF 融合", summary["rrf"], "#57d39d"),
]

canvas = Image.new("RGB", (1500, 900), "#0c1422")
draw = ImageDraw.Draw(canvas)
draw.text((76, 58), "RRF 冻结候选排序实验", font=font(50, True), fill="#f5f7fb")
draw.text((76, 126), "32 条文档 · 16 条查询 · 两路 top-5 · k=60", font=font(25), fill="#adb8ca")

for index, (label, values, color) in enumerate(rows):
    top = 235 + index * 180
    draw.text((76, top), label, font=font(32, True), fill="#f5f7fb")
    for metric_index, (metric, display) in enumerate((("hitAt1", "Hit@1"), ("hitAt3", "Hit@3"), ("mrr", "MRR"))):
        x = 360 + metric_index * 360
        value = values[metric]
        normalized = value / data["queryCount"] if metric.startswith("hit") else value
        draw.rounded_rectangle((x, top - 4, x + 260, top + 48), radius=16, fill="#202c40")
        draw.rounded_rectangle((x, top - 4, x + max(7, int(260 * normalized)), top + 48), radius=16, fill=color)
        shown = f"{value}/{data['queryCount']}" if metric.startswith("hit") else f"{value:.3f}"
        draw.text((x, top + 64), f"{display}  {shown}", font=font(22, True), fill=color)

draw.rounded_rectangle((76, 790, 1420, 850), radius=18, fill="#172236")
draw.text((102, 806), "两条查询未进入任一路候选，RRF 也无法恢复；语义候选为冻结代理，不是 embedding 基准", font=font(21), fill="#c7d0dd")
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
canvas.save(OUTPUT)

cover = Image.new("RGB", (1600, 900), "#101827")
cdraw = ImageDraw.Draw(cover)
cdraw.text((90, 90), "RRF 混合检索", font=font(66, True), fill="#f5f7fb")
cdraw.text((90, 180), "排序实验", font=font(66, True), fill="#5ed3a0")
cdraw.text((94, 290), "冻结两路 top-5，只比较名次融合", font=font(31), fill="#b8c3d4")
for index, (label, color) in enumerate((("词面", "#6aa9ff"), ("语义代理", "#ee9a68"), ("RRF", "#57d39d"))):
    x = 110 + index * 480
    cdraw.rounded_rectangle((x, 455, x + 390, 650), radius=30, fill="#1c2a40")
    cdraw.text((x + 38, 495), label, font=font(35, True), fill=color)
    values = rows[index][1]
    cdraw.text((x + 38, 560), f"Hit@1  {values['hitAt1']}/16", font=font(26), fill="#eef3f9")
cdraw.text((94, 790), "两条双路漏召回保持不可恢复", font=font(26), fill="#ef9a77")
cover.save(COVER)

code = Image.new("RGB", (1500, 900), "#0b1220")
kdraw = ImageDraw.Draw(code)
kdraw.text((78, 58), "Reciprocal Rank Fusion", font=font(46, True), fill="#f5f7fb")
kdraw.text((78, 128), "score(d) = Σ  1 / (60 + rankᵢ(d))", font=font(37, True), fill="#61d29e")
snippet = [
    "for ranked in lists:",
    "    for rank, doc_id in enumerate(ranked, start=1):",
    "        scores[doc_id] += 1.0 / (k + rank)",
    "return sorted(scores, key=lambda id: (-scores[id], id))",
]
for index, line in enumerate(snippet):
    kdraw.text((105, 270 + index * 95), line, font=font(28), fill="#dce6f3" if index != 2 else "#f0cf76")
kdraw.rounded_rectangle((82, 700, 1418, 820), radius=22, fill="#18253a")
kdraw.text((112, 728), "共同靠前会累计两份分数；未进入候选的文档得分为零", font=font(26), fill="#b8c4d4")
code.save(CODE)
print(OUTPUT)
print(COVER)
print(CODE)
