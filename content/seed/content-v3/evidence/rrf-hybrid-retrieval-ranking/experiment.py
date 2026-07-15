from __future__ import annotations

import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
TOP_K = 5
RRF_K = 60


def rrf(lists: list[list[str]], k: int = RRF_K) -> list[str]:
    scores: dict[str, float] = {}
    for ranked in lists:
        for rank, doc_id in enumerate(ranked, start=1):
            scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (k + rank)
    return sorted(scores, key=lambda doc_id: (-scores[doc_id], doc_id))


def rank_of(ranked: list[str], expected: str) -> int | None:
    try:
        return ranked.index(expected) + 1
    except ValueError:
        return None


def metrics(ranks: list[int | None]) -> dict[str, float | int]:
    total = len(ranks)
    return {
        "hitAt1": sum(rank == 1 for rank in ranks),
        "hitAt3": sum(rank is not None and rank <= 3 for rank in ranks),
        "mrr": round(sum(0.0 if rank is None else 1.0 / rank for rank in ranks) / total, 6),
    }


corpus = json.loads((HERE / "corpus.json").read_text(encoding="utf-8"))
queries = json.loads((HERE / "queries.json").read_text(encoding="utf-8"))
rankings = json.loads((HERE / "rankings.json").read_text(encoding="utf-8"))

doc_ids = {item["id"] for item in corpus}
query_by_id = {item["id"]: item for item in queries}
assert len(corpus) >= 30 and len(doc_ids) == len(corpus)
assert len(queries) >= 16 and len(query_by_id) == len(queries)
assert len(rankings) == len(queries)
assert all(len(case["lexical"]) == TOP_K for case in rankings)
assert all(len(case["semanticProxy"]) == TOP_K for case in rankings)
assert all(set(case["lexical"] + case["semanticProxy"]) <= doc_ids for case in rankings)
assert all(query_by_id[case["queryId"]]["expected"] == case["expected"] for case in rankings)
assert sum(case["expected"] not in case["lexical"] + case["semanticProxy"] for case in rankings) >= 2

per_query = []
rank_sets: dict[str, list[int | None]] = {"lexical": [], "semanticProxy": [], "rrf": []}
for case in rankings:
    fused = rrf([case["lexical"], case["semanticProxy"]])
    ranks = {
        "lexical": rank_of(case["lexical"], case["expected"]),
        "semanticProxy": rank_of(case["semanticProxy"], case["expected"]),
        "rrf": rank_of(fused, case["expected"]),
    }
    for strategy, rank in ranks.items():
        rank_sets[strategy].append(rank)
    per_query.append({
        "queryId": case["queryId"],
        "query": query_by_id[case["queryId"]]["text"],
        "expected": case["expected"],
        "presentInAnyCandidateList": case["expected"] in case["lexical"] + case["semanticProxy"],
        "lexical": case["lexical"],
        "semanticProxy": case["semanticProxy"],
        "rrf": fused,
        "expectedRanks": ranks,
    })

result = {
    "experiment": "deterministic frozen-candidate RRF comparison",
    "limitations": "semanticProxy is a hand-frozen ordered candidate list, not an embedding or LLM benchmark",
    "corpusSize": len(corpus),
    "queryCount": len(queries),
    "topK": TOP_K,
    "rrfK": RRF_K,
    "unrecoverableQueryCount": sum(not item["presentInAnyCandidateList"] for item in per_query),
    "strategies": {name: metrics(ranks) for name, ranks in rank_sets.items()},
    "queries": per_query,
}

assert "rrf" in result["strategies"]
assert result["unrecoverableQueryCount"] >= 2
assert all(
    item["expectedRanks"]["rrf"] is None
    for item in per_query
    if not item["presentInAnyCandidateList"]
)

(HERE / "results.json").write_text(
    json.dumps(result, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(json.dumps(result["strategies"], ensure_ascii=False))
