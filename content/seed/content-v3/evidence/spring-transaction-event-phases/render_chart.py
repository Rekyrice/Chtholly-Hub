from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
OUTPUT = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "spring-transaction-event-phases-chart.png"
COVER = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "spring-transaction-event-phases-cover.png"
CODE = REPO / ".codex-tmp" / "seed-content-v3" / "sources" / "spring-transaction-event-phases-code.png"


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
observations = data["observations"]
scenarios = {
    "commit": [row for row in observations if row["scenario"] == "commit"],
    "rollback": [row for row in observations if row["scenario"] == "rollback"],
}

canvas = Image.new("RGB", (1500, 900), "#0d1422")
draw = ImageDraw.Draw(canvas)
draw.text((76, 58), "Spring 事务事件：实际调用时间线", font=font(48, True), fill="#f4f7fb")
draw.text((76, 124), "Spring 6.1.6 · H2 2.2.224 · READ_COMMITTED · 独立连接探测", font=font(24), fill="#aeb9ca")

colors = {0: "#f39a75", 1: "#5ed0a0"}
for row_index, (scenario, rows) in enumerate(scenarios.items()):
    top = 245 + row_index * 270
    label = "提交" if scenario == "commit" else "回滚"
    draw.text((76, top - 16), label, font=font(34, True), fill="#f4f7fb")
    x0, x1 = 250, 1390
    y = top + 62
    draw.line((x0, y, x1, y), fill="#3b4960", width=6)
    for index, item in enumerate(rows):
        x = x0 + int((x1 - x0) * index / max(1, len(rows) - 1))
        visible = item["independentConnectionRowCount"]
        draw.ellipse((x - 18, y - 18, x + 18, y + 18), fill=colors[visible])
        phase = item["phase"].replace("_", "\n")
        draw.multiline_text((x - 78, y + 34), phase, font=font(22, True), fill="#e7edf6", align="center", spacing=2)
        text = "独立连接可见" if visible else "独立连接不可见"
        draw.text((x - 78, y + 108), text, font=font(18), fill=colors[visible])

draw.rounded_rectangle((76, 785, 1390, 850), radius=18, fill="#172236")
draw.text((105, 803), "橙色：目标行不可见    绿色：目标行已提交可见    回滚路径从未出现 after_commit", font=font(22), fill="#c5cfdd")
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
canvas.save(OUTPUT)

cover = Image.new("RGB", (1600, 900), "#101827")
cdraw = ImageDraw.Draw(cover)
cdraw.text((90, 92), "Spring 事务事件", font=font(66, True), fill="#f5f7fb")
cdraw.text((90, 181), "提交时机实验", font=font(66, True), fill="#67d6a2")
cdraw.text((94, 292), "另一条 JDBC 连接究竟从哪一步看见数据？", font=font(30), fill="#b8c3d4")
phases = [("ordinary", "0"), ("before commit", "0"), ("after commit", "1"), ("completion", "1")]
for index, (phase, visible) in enumerate(phases):
    x = 100 + index * 365
    cdraw.rounded_rectangle((x, 450, x + 310, 650), radius=28, fill="#1c2a40")
    cdraw.text((x + 28, 486), phase, font=font(27, True), fill="#eef3f9")
    color = "#61d29e" if visible == "1" else "#ef9a77"
    cdraw.text((x + 28, 548), f"独立连接行数  {visible}", font=font(23), fill=color)
    if index < len(phases) - 1:
        cdraw.text((x + 322, 525), "→", font=font(38, True), fill="#73839a")
cdraw.text((94, 800), "Java 21 · Spring 6.1.6 · H2 2.2.224", font=font(24), fill="#8391a6")
cover.save(COVER)

code = Image.new("RGB", (1500, 900), "#0b1220")
kdraw = ImageDraw.Draw(code)
kdraw.text((78, 58), "五个监听入口", font=font(46, True), fill="#f5f7fb")
kdraw.text((78, 118), "同一个事件，不同的事务完成位置", font=font(25), fill="#aeb9ca")
lines = [
    ("@EventListener", "ordinary(event);", "发布时同步执行"),
    ("@TransactionalEventListener(BEFORE_COMMIT)", "beforeCommit(event);", "提交前，可阻止提交"),
    ("@TransactionalEventListener(AFTER_COMMIT)", "afterCommit(event);", "提交后，主数据已可见"),
    ("@TransactionalEventListener(AFTER_ROLLBACK)", "afterRollback(event);", "只在回滚后"),
    ("@TransactionalEventListener(AFTER_COMPLETION)", "afterCompletion(event);", "提交或回滚后"),
]
for index, (annotation, call, note) in enumerate(lines):
    y = 215 + index * 124
    kdraw.text((90, y), annotation, font=font(24, True), fill="#72b5ff")
    kdraw.text((600, y), call, font=font(24), fill="#f0cf76")
    kdraw.text((1030, y), note, font=font(22), fill="#b7c2d2")
kdraw.text((90, 825), "每次回调都用新 JDBC 连接查询目标主键", font=font(23), fill="#62d3a0")
code.save(CODE)
print(OUTPUT)
print(COVER)
print(CODE)
