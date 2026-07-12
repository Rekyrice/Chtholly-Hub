#!/usr/bin/env python3
"""Reproducible local TF-IDF chunk experiment locked to one Git commit."""

import argparse
import hashlib
import json
import math
import pathlib
import re
import os
import shutil
import stat
import subprocess
import sys
import tempfile
from collections import Counter


CORPUS_COMMIT = "c9f0747298c1267e4d1e3767fdfc5fb12185cb7e"
CORPUS_PATHS = [
    "README.md",
    "AGENTS.md",
    "apps/server/README.md",
    "apps/server/src/main/resources/knowledge/about-me.md",
    "apps/server/src/main/resources/knowledge/my-home.md",
    "apps/server/src/main/resources/knowledge/my-world.md",
    "apps/server/src/main/resources/knowledge/those-stories.md",
]
CHUNK_SIZES = (500, 800)
OVERLAP = 80
TOP_K = 3
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
TOKEN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]|[A-Za-z0-9]+")
HAN_RUN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]+")


def git_output(repo_root, *arguments):
    command = ["git", "-C", str(repo_root), *arguments]
    try:
        return subprocess.check_output(command, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exception:
        detail = exception.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError("git command failed: " + " ".join(command) + (": " + detail if detail else "")) from exception


def find_repo_root():
    try:
        raw = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exception:
        detail = exception.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError("git rev-parse --show-toplevel failed" + (": " + detail if detail else "")) from exception
    return pathlib.Path(raw.decode("utf-8").strip()).resolve()


def read_git_blob(repo_root, commit, source_path):
    raw = git_output(repo_root, "show", commit + ":" + source_path)
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as exception:
        raise RuntimeError("corpus blob is not UTF-8: " + source_path) from exception


def parse_sections(text):
    sections = []
    heading = "文档开头"
    lines = []
    fence = None
    for line in text.splitlines(keepends=True):
        stripped = line.lstrip()
        marker = stripped[:3] if stripped.startswith(("```", "~~~")) else None
        if marker:
            fence = marker if fence is None else (None if fence == marker else fence)
            lines.append(line)
            continue
        match = HEADING_RE.match(line.rstrip("\r\n")) if fence is None else None
        if match:
            if lines:
                sections.append((heading, "".join(lines)))
            heading = match.group(2)
            lines = [line]
        else:
            lines.append(line)
    if lines:
        sections.append((heading, "".join(lines)))
    return sections


def chunk_documents(documents, chunk_size):
    chunks = []
    step = chunk_size - OVERLAP
    for source_path in CORPUS_PATHS:
        chunk_index = 0
        for heading, section in parse_sections(documents[source_path]):
            start = 0
            while section and start < len(section):
                text = section[start : start + chunk_size]
                chunks.append({
                    "source_path": source_path,
                    "heading": heading,
                    "chunk_index": chunk_index,
                    "start_char": start,
                    "end_char": start + len(text),
                    "text": text,
                })
                chunk_index += 1
                if start + chunk_size >= len(section):
                    break
                start += step
    return chunks


def tokenize(text):
    lowered = text.lower()
    tokens = TOKEN_RE.findall(lowered)
    for run in HAN_RUN_RE.findall(lowered):
        tokens.extend(left + right for left, right in zip(run, run[1:]))
    return tokens


def is_relevant(chunk, query):
    return (
        chunk["source_path"] == query["expected_source_path"]
        and chunk["heading"] == query["expected_heading"]
        and any(evidence in chunk["text"] for evidence in query["evidence_substrings"])
    )


def load_queries(path):
    payload = json.loads(path.read_text(encoding="utf-8"))
    queries = payload.get("queries")
    if not isinstance(queries, list) or not queries:
        raise RuntimeError("queries.json must contain a non-empty queries list")
    required = {"id", "query", "expected_source_path", "expected_heading", "evidence_substrings"}
    seen = set()
    for query in queries:
        missing = required.difference(query)
        if missing:
            raise RuntimeError("query is missing fields: " + ", ".join(sorted(missing)))
        if query["id"] in seen:
            raise RuntimeError("duplicate query id: " + query["id"])
        seen.add(query["id"])
        if query["expected_source_path"] not in CORPUS_PATHS:
            raise RuntimeError("query source is outside the fixed corpus: " + query["id"])
        if not query["evidence_substrings"] or not all(isinstance(value, str) and value for value in query["evidence_substrings"]):
            raise RuntimeError("query evidence_substrings must be non-empty strings: " + query["id"])
    return queries


def validate_frozen_evidence(documents, queries):
    for query in queries:
        matching_sections = [
            text for heading, text in parse_sections(documents[query["expected_source_path"]])
            if heading == query["expected_heading"]
        ]
        if not matching_sections:
            raise RuntimeError("expected heading is absent at frozen commit: " + query["id"])
        if not any(any(evidence in section for evidence in query["evidence_substrings"]) for section in matching_sections):
            raise RuntimeError("frozen evidence is absent from expected heading: " + query["id"])


def vectorize(counter, idf):
    return {token: count * idf.get(token, idf[None]) for token, count in counter.items()}


def cosine(left, right):
    numerator = sum(value * right.get(token, 0.0) for token, value in left.items())
    left_norm = math.sqrt(sum(value * value for value in left.values()))
    right_norm = math.sqrt(sum(value * value for value in right.values()))
    return numerator / (left_norm * right_norm) if left_norm and right_norm else 0.0


def public_result(rank, score, chunk, relevant):
    return {
        "rank": rank,
        "score": round(score, 8),
        "source_path": chunk["source_path"],
        "heading": chunk["heading"],
        "chunk_index": chunk["chunk_index"],
        "start_char": chunk["start_char"],
        "end_char": chunk["end_char"],
        "is_relevant": relevant,
    }


def run_size(documents, queries, chunk_size):
    chunks = chunk_documents(documents, chunk_size)
    for query in queries:
        if not any(is_relevant(chunk, query) for chunk in chunks):
            raise RuntimeError("no answer-bearing chunk for query at chunk size " + str(chunk_size) + ": " + query["id"])
    counters = [Counter(tokenize(chunk["text"])) for chunk in chunks]
    document_frequency = Counter(token for counter in counters for token in counter)
    total = len(chunks)
    idf = {token: math.log((total + 1) / (frequency + 1)) + 1 for token, frequency in document_frequency.items()}
    idf[None] = math.log(total + 1) + 1
    vectors = [vectorize(counter, idf) for counter in counters]
    query_results = []
    hit_count = 0
    reciprocal_ranks = []
    for query in queries:
        query_vector = vectorize(Counter(tokenize(query["query"])), idf)
        ranked = [(cosine(query_vector, vector), chunk) for vector, chunk in zip(vectors, chunks)]
        ranked.sort(key=lambda item: (-item[0], item[1]["source_path"], item[1]["chunk_index"]))
        relevant_rank = next(rank for rank, (_, chunk) in enumerate(ranked, start=1) if is_relevant(chunk, query))
        hit_count += int(relevant_rank == 1)
        reciprocal_ranks.append(1.0 / relevant_rank)
        top_results = [
            public_result(rank, score, chunk, is_relevant(chunk, query))
            for rank, (score, chunk) in enumerate(ranked[:TOP_K], start=1)
        ]
        expected_score, expected_chunk = ranked[relevant_rank - 1]
        query_results.append({
            **query,
            "expected_rank": relevant_rank,
            "expected_result": public_result(relevant_rank, expected_score, expected_chunk, True),
            "top_results": top_results,
        })
    return {
        "chunk_size": chunk_size,
        "overlap": OVERLAP,
        "chunk_count": len(chunks),
        "metrics": {
            "hit_at_1": round(hit_count / len(queries), 6),
            "mrr": round(sum(reciprocal_ranks) / len(queries), 6),
        },
        "queries": query_results,
    }


def build_payload(repo_root, queries_path):
    documents = {path: read_git_blob(repo_root, CORPUS_COMMIT, path) for path in CORPUS_PATHS}
    queries = load_queries(queries_path)
    validate_frozen_evidence(documents, queries)
    corpus = [
        {"path": path, "sha256": hashlib.sha256(documents[path].encode("utf-8")).hexdigest()}
        for path in CORPUS_PATHS
    ]
    return {
        "experiment": "rag-chunk-size-tfidf-answer-bearing-baseline",
        "corpus_commit": CORPUS_COMMIT,
        "corpus": corpus,
        "corpus_count": len(corpus),
        "query_count": len(queries),
        "top_k": TOP_K,
        "chunk_unit": "Unicode characters (Python string code points)",
        "chunk_boundary": "Markdown heading sections, fixed windows, step = chunk_size - overlap",
        "tokenizer": "Chinese single characters and adjacent bigrams inside contiguous Han runs plus lowercase English/number tokens",
        "relevance": "source_path and heading match, and the same chunk contains at least one frozen evidence substring",
        "retrieval": "TF-IDF cosine, score descending then source_path and chunk_index ascending",
        "runs": [run_size(documents, queries, size) for size in CHUNK_SIZES],
    }


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n")


def remove_readonly(function, path, _error_info):
    os.chmod(path, stat.S_IWRITE)
    function(path)


def run_self_tests(repo_root):
    passed = 0

    def verify(condition, label):
        nonlocal passed
        if not condition:
            raise AssertionError("self-test failed: " + label)
        passed += 1

    query = {"expected_source_path": "doc.md", "expected_heading": "H", "evidence_substrings": ["答案片段"]}
    verify(not is_relevant({"source_path": "doc.md", "heading": "H", "text": "同标题无答案"}, query), "same heading without evidence")
    verify(is_relevant({"source_path": "doc.md", "heading": "H", "text": "包含答案片段"}, query), "same heading with evidence")
    split = {"expected_source_path": "doc.md", "expected_heading": "H", "evidence_substrings": ["完整答案"]}
    verify(not any(is_relevant(chunk, split) for chunk in [
        {"source_path": "doc.md", "heading": "H", "text": "完整"},
        {"source_path": "doc.md", "heading": "H", "text": "答案"},
    ]), "evidence split across chunks")
    verify("中文" in tokenize("中文"), "contiguous Han bigram")
    for separated in ("中 abc 文", "中，文", "中\n文"):
        verify("中文" not in tokenize(separated), "Han bigram boundary: " + repr(separated))
    headings = [heading for heading, _ in parse_sections(
        "# Root\n```powershell\n# fake heading\n```\n## Real\nbody\n"
    )]
    verify(headings == ["Root", "Real"], "fenced fake heading")
    temp_parent = repo_root / ".codex-tmp"
    temp_parent.mkdir(exist_ok=True)
    directory = tempfile.mkdtemp(dir=str(temp_parent))
    try:
        fixture = pathlib.Path(directory)
        subprocess.run(["git", "init", "-q"], cwd=str(fixture), check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=str(fixture), check=True)
        subprocess.run(["git", "config", "user.name", "test"], cwd=str(fixture), check=True)
        (fixture / "doc.md").write_text("# H\n提交内容\n", encoding="utf-8")
        subprocess.run(["git", "add", "doc.md"], cwd=str(fixture), check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=str(fixture), check=True)
        commit = git_output(fixture, "rev-parse", "HEAD").decode("utf-8").strip()
        before = read_git_blob(fixture, commit, "doc.md")
        (fixture / "doc.md").write_text("# H\n工作树变化\n", encoding="utf-8")
        verify(read_git_blob(fixture, commit, "doc.md") == before, "git blob ignores worktree change")
    finally:
        shutil.rmtree(directory, onerror=remove_readonly)
    print("self-tests: " + str(passed) + " passed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--queries", type=pathlib.Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        repo_root = find_repo_root()
        if args.self_test:
            run_self_tests(repo_root)
            return 0
        if args.output is None:
            parser.error("--output is required unless --self-test is used")
        queries_path = args.queries or pathlib.Path(__file__).with_name("queries.json")
        write_json(args.output, build_payload(repo_root, queries_path))
        return 0
    except (RuntimeError, OSError, json.JSONDecodeError) as exception:
        print("experiment failed: " + str(exception), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
