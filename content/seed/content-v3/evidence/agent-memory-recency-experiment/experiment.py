from __future__ import annotations

import json
import re
from pathlib import Path


HERE = Path(__file__).resolve().parent


def features(text: str) -> set[str]:
    normalized = text.lower()
    latin = set(re.findall(r"[a-z0-9._/-]+", normalized))
    han_runs = re.findall(r"[\u3400-\u9fff]+", normalized)
    han_chars = {char for run in han_runs for char in run}
    han_bigrams = {
        run[index : index + 2]
        for run in han_runs
        for index in range(max(0, len(run) - 1))
    }
    return latin | han_chars | han_bigrams


def score(query: str, memory: dict) -> int:
    return len(features(query) & features(memory["content"]))


def select(query: str, memories: list[dict]) -> tuple[dict, int]:
    ranked = sorted(
        memories,
        key=lambda memory: (-score(query, memory), memory["createdAt"], memory["id"]),
    )
    winner = ranked[0]
    return winner, score(query, winner)


def active_memories(memories: list[dict]) -> list[dict]:
    superseded = {memory["supersedes"] for memory in memories if memory.get("supersedes")}
    return [memory for memory in memories if memory["id"] not in superseded]


memories = json.loads((HERE / "memories.json").read_text(encoding="utf-8"))
queries = json.loads((HERE / "queries.json").read_text(encoding="utf-8"))
active = active_memories(memories)
superseded_ids = {memory["supersedes"] for memory in memories if memory.get("supersedes")}
predictions = []

for case in queries:
    baseline, baseline_score = select(case["query"], memories)
    recency, recency_score = select(case["query"], active)
    predictions.append(
        {
            **case,
            "baseline": baseline["id"],
            "baselineScore": baseline_score,
            "recencyAware": recency["id"],
            "recencyAwareScore": recency_score,
            "baselineCorrect": baseline["id"] == case["expected"],
            "recencyAwareCorrect": recency["id"] == case["expected"],
            "baselineStaleRecall": baseline["id"] in superseded_ids,
            "recencyAwareStaleRecall": recency["id"] in superseded_ids,
        }
    )

result = {
    "experiment": "agent-memory-recency-experiment",
    "strategy": {
        "baseline": "character, Han bigram and Latin token overlap; oldest wins ties",
        "recencyAware": "remove memories referenced by supersedes, then use baseline ranking",
    },
    "memoryCount": len(memories),
    "activeMemoryCount": len(active),
    "queryCount": len(queries),
    "summary": {
        "baselineCorrect": sum(item["baselineCorrect"] for item in predictions),
        "baselineIncorrect": sum(not item["baselineCorrect"] for item in predictions),
        "baselineStaleRecall": sum(item["baselineStaleRecall"] for item in predictions),
        "recencyAwareCorrect": sum(item["recencyAwareCorrect"] for item in predictions),
        "recencyAwareIncorrect": sum(not item["recencyAwareCorrect"] for item in predictions),
        "recencyAwareStaleRecall": sum(item["recencyAwareStaleRecall"] for item in predictions),
    },
    "predictions": predictions,
    "limitations": [
        "deterministic lexical retrieval, not an embedding or LLM benchmark",
        "supersedes relationships are curated and known before retrieval",
        "a pilot with systematically longer old memories was discarded before this balanced set was frozen",
    ],
}

(HERE / "results.json").write_text(
    json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
print(json.dumps(result, ensure_ascii=False, indent=2))
