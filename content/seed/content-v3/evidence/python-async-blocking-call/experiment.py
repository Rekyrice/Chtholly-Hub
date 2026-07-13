from __future__ import annotations

import asyncio
import json
import math
import platform
import statistics
import time
from pathlib import Path


TASKS = 50
DELAY_SECONDS = 0.05
ROUNDS = 5
RESULTS = Path(__file__).with_name("results.json")


async def direct_blocking() -> None:
    time.sleep(DELAY_SECONDS)


async def cooperative_wait() -> None:
    await asyncio.sleep(DELAY_SECONDS)


async def thread_offload() -> None:
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, time.sleep, DELAY_SECONDS)


async def measure(worker) -> float:
    started = time.perf_counter()
    await asyncio.gather(*(worker() for _ in range(TASKS)))
    return time.perf_counter() - started


def percentile(values: list[float], percentile_value: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(percentile_value * len(ordered)) - 1)
    return ordered[index]


async def run() -> dict:
    workers = {
        "direct_blocking": direct_blocking,
        "cooperative_wait": cooperative_wait,
        "thread_offload": thread_offload,
    }
    raw: dict[str, list[float]] = {name: [] for name in workers}
    for _ in range(ROUNDS):
        for name, worker in workers.items():
            raw[name].append(await measure(worker))

    summary = {
        name: {
            "roundSeconds": [round(value, 6) for value in values],
            "medianSeconds": round(statistics.median(values), 6),
            "p95Seconds": round(percentile(values, 0.95), 6),
        }
        for name, values in raw.items()
    }
    baseline = summary["direct_blocking"]["medianSeconds"]
    for name in summary:
        summary[name]["speedupVsDirect"] = round(
            baseline / summary[name]["medianSeconds"], 2
        )

    return {
        "experiment": "python-async-blocking-call",
        "python": platform.python_version(),
        "platform": platform.platform(),
        "tasks": TASKS,
        "delaySeconds": DELAY_SECONDS,
        "rounds": ROUNDS,
        "summary": summary,
        "notes": [
            "cooperative_wait simulates an awaitable dependency; it is not an HTTP benchmark",
            "thread_offload uses the default executor and remains bounded by its thread count",
        ],
    }


if __name__ == "__main__":
    result = asyncio.run(run())
    RESULTS.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
