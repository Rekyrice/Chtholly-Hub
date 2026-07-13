#!/usr/bin/env python3
"""Deterministic lexical routing proxy for comparing verbose and concise tool descriptions."""

import json
import math
import pathlib
import re
import sys
from collections import Counter


TOKEN_RE = re.compile(r"[a-z0-9_]+|[\u4e00-\u9fff]")


def tokens(text):
    lowered = text.lower()
    base = TOKEN_RE.findall(lowered)
    han = "".join(char for char in lowered if "\u4e00" <= char <= "\u9fff")
    return base + [han[index : index + 2] for index in range(max(0, len(han) - 1))]


def score(query, description):
    query_count = Counter(tokens(query))
    desc_count = Counter(tokens(description))
    shared = set(query_count) & set(desc_count)
    return sum((1 + math.log(query_count[token])) * (1 + math.log(desc_count[token])) for token in shared)


root = pathlib.Path(__file__).resolve().parent
cases = json.loads((root / "cases.json").read_text(encoding="utf-8"))
tools = json.loads((root / "tools.json").read_text(encoding="utf-8"))
results = {"experiment": "agent-tool-description-ablation", "selector": "deterministic lexical overlap", "runs": {}}

for variant in ("verbose", "concise"):
    predictions = []
    correct = 0
    for case in cases:
        ranked = sorted(
            ((name, score(case["prompt"], values[variant])) for name, values in tools.items()),
            key=lambda item: (-item[1], item[0]),
        )
        predicted = ranked[0][0]
        is_correct = predicted == case["expected"]
        correct += int(is_correct)
        predictions.append({
            "id": case["id"],
            "expected": case["expected"],
            "predicted": predicted,
            "correct": is_correct,
            "topScore": round(ranked[0][1], 6),
            "runnerUp": ranked[1][0],
        })
    results["runs"][variant] = {
        "cases": len(cases),
        "correct": correct,
        "errors": len(cases) - correct,
        "accuracy": round(correct / len(cases), 6),
        "predictions": predictions,
    }

pathlib.Path(sys.argv[1]).write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({name: {key: value for key, value in run.items() if key != "predictions"} for name, run in results["runs"].items()}, ensure_ascii=False))
