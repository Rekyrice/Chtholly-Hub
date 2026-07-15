#!/usr/bin/env python3
"""Render concise-vs-verbose routing results as a PNG."""

import json
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont


def font(size, bold=False):
    path = pathlib.Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc")
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default()


data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
image = Image.new("RGB", (1400, 820), "#0B1020")
draw = ImageDraw.Draw(image)
draw.text((80, 65), "工具说明越长，路由信息未必越多", fill="#F8FAFC", font=font(44, True))
draw.text((80, 128), "24 条冻结提示词 · 6 个工具 · 确定性词面重叠选择器", fill="#94A3B8", font=font(24))
for index, variant in enumerate(("verbose", "concise")):
    run = data["runs"][variant]
    x = 100 + index * 650
    color = "#FB7185" if variant == "verbose" else "#34D399"
    title = "原始长说明" if variant == "verbose" else "砍短后的说明"
    draw.rounded_rectangle((x, 220, x + 560, 690), radius=28, fill="#172033")
    draw.text((x + 40, 260), title, fill="#E2E8F0", font=font(31, True))
    draw.text((x + 40, 350), f"{run['correct']} / {run['cases']}", fill=color, font=font(84, True))
    draw.text((x + 42, 455), "选择正确", fill="#CBD5E1", font=font(26))
    draw.text((x + 40, 535), f"错误 {run['errors']} 次", fill="#E2E8F0", font=font(34, True))
    draw.rounded_rectangle((x + 40, 610, x + 510, 638), radius=14, fill="#334155")
    draw.rounded_rectangle((x + 40, 610, x + 40 + int(470 * run['accuracy']), 638), radius=14, fill=color)
draw.text((80, 750), "这不是 LLM 基准：它只验证描述中的重叠词会怎样干扰一个可复现的路由代理。", fill="#94A3B8", font=font(22))
pathlib.Path(sys.argv[2]).parent.mkdir(parents=True, exist_ok=True)
image.save(sys.argv[2], "PNG", optimize=True)
