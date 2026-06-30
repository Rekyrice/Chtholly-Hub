"""从 picked-voices.csv 生成 lib/live2d/tapLines.ts"""
import csv
import json
from pathlib import Path

CSV = Path(__file__).resolve().parents[1] / "docs/live2d/picked-voices.csv"
OUT = Path(__file__).resolve().parents[1] / "apps/web/lib/live2d/tapLines.ts"

EXPR = {
    1: "smile", 2: "neutral", 3: "smile", 4: "neutral", 5: "neutral",
    6: "neutral", 7: "neutral", 8: "neutral", 9: "neutral", 10: "smile",
    11: "neutral", 12: "sad", 13: "smile", 14: "smile", 15: "neutral",
    16: "neutral", 17: "sad", 18: "smile", 19: "smile", 20: "neutral",
    21: "neutral", 22: "neutral", 23: "sad", 24: "smile", 25: "neutral",
    26: "smile", 27: "sad", 28: "sad", 30: "sad",
    31: "smile", 32: "neutral", 33: "smile", 35: "smile",
    36: "smile", 37: "smile", 38: "neutral", 39: "smile", 40: "smile",
}

lines = []
with CSV.open(encoding="utf-8-sig") as f:
    for row in csv.DictReader(f):
        fname = row["新文件名"].strip()
        num = int(fname.replace("tap-chtholly-line-", "").replace(".wav", ""))
        lines.append({
            "id": f"line-{num:02d}",
            "sound": f"motions/tap/{fname}",
            "motionIndex": (num - 1) % 3,
            "expression": EXPR[num],
            "textJa": row["日文原文"].strip(),
            "textZh": row["中文翻译"].strip(),
            "durationSec": float(row["时长(秒)"]),
        })

header = """import { CHTHOLLY_EXPRESSION } from "@/lib/live2d/constants";

/** 珂朵莉点击语音：表情键名 */
export type ChthollyTapExpression = keyof typeof CHTHOLLY_EXPRESSION;

export type ChthollyTapLine = {
  id: string;
  sound: string;
  motionIndex: number;
  expression: ChthollyTapExpression;
  /** 日语原文，\" / \" 分隔多句 */
  textJa: string;
  textZh: string;
  durationSec: number;
};

/** 将台词转为展示用行（按 \" / \" 分段） */
export function formatTapLineJa(textJa: string): string[] {
  return textJa.split(" / ").map((s) => s.trim()).filter(Boolean);
}

export const CHTHOLLY_TAP_LINES: ChthollyTapLine[] = """

OUT.write_text(header + json.dumps(lines, ensure_ascii=False, indent=2) + ";\n\nexport const CHTHOLLY_TAP_MOTION_COUNT = 3;\n", encoding="utf-8")
print(f"wrote {len(lines)} lines -> {OUT}")
