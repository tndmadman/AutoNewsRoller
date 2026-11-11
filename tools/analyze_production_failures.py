#!/usr/bin/env python3
"""Read-only production failure inventory. No job state is modified or requeued."""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from pathlib import Path


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as source:
        data = json.load(source)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a JSON object")
    return data


def records(path: Path):
    with path.open("r", encoding="utf-8", errors="replace") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                yield value


def summarize(state: dict, events: list[dict] | None = None,
              logs: list[str] | None = None,
              checkpoints: list[dict] | None = None) -> dict:
    stories = state.get("stories") or {}
    if isinstance(stories, list):
        stories = {str(index): story for index, story in enumerate(stories)}
    if not isinstance(stories, dict):
        stories = {}
    statuses: Counter[str] = Counter()
    categories: Counter[str] = Counter()
    stages: Counter[str] = Counter()
    repeats: list[dict] = []
    failure_rows: list[dict] = []
    attempts_total = 0
    completed_attempts = []
    for story_id, row in stories.items():
        if not isinstance(row, dict):
            continue
        status = str(row.get("status") or "UNKNOWN")
        statuses[status] += 1
        claimed = row.get("claimCount")
        if isinstance(claimed, (int, float)):
            attempts_total += max(0, int(claimed))
            if status == "COMPLETE":
                completed_attempts.append(max(1, int(claimed)))
        history = row.get("productionAttempts") or []
        failures = [
            entry for entry in history
            if isinstance(entry, dict) and entry.get("event") == "FAILED"
        ] if isinstance(history, list) else []
        if not failures and row.get("lastFailure"):
            failures = [{
                "failureType": row.get("failureType") or "UNKNOWN_FAILURE",
                "stage": row.get("lastFailedStage") or row.get("stage") or "UNKNOWN",
                "error": row.get("lastFailure"),
                "at": row.get("lastFailedAt"),
            }]
        if len(failures) >= 2:
            repeats.append({"storyId": story_id, "count": len(failures)})
        for item in failures:
            category = str(item.get("failureType") or "UNKNOWN_FAILURE")
            stage = str(item.get("stage") or "UNKNOWN")
            categories[category] += 1
            stages[stage] += 1
            failure_rows.append({
                "storyId": str(story_id), "category": category, "stage": stage,
                "time": str(item.get("at") or ""), "error": str(item.get("error") or "")[:1000],
            })
    event_counter: Counter[str] = Counter()
    for entry in events or []:
        kind = str(entry.get("type") or entry.get("event") or "UNKNOWN")
        payload = entry.get("payload")
        if isinstance(payload, dict):
            kind += ":" + str(payload.get("stage") or payload.get("event") or "")
        event_counter[kind] += 1
    log_counter: Counter[str] = Counter()
    for line in logs or []:
        normalized = line.upper()
        for token in ("COMFYUI FAILED", "COMFY_TIMEOUT", "IMAGE REJECTED",
                      "SCRIPT_VALIDATION_FAILED", "LEASE_EXPIRED", "UPLOAD_FAILURE",
                      "RESUMED VALIDATED", "COMFYUI REUSED"):
            if token in normalized:
                log_counter[token] += 1
    checkpoint_summary = {
        "manifestsRead": len(checkpoints or []),
        "recordedStageEntries": sum(
            len(item.get("entries", {}))
            for item in checkpoints or []
            if isinstance(item, dict) and isinstance(item.get("entries"), dict)
        ),
    }
    completed = statuses["COMPLETE"]
    failed = statuses["FAILED"]
    terminal = completed + failed
    return {
        "basis": "observed controller records, optional events/logs, and manifests",
        "stories": len(stories),
        "statuses": dict(sorted(statuses.items())),
        "completed": completed,
        "failed": failed,
        "terminalSuccessRate": round(completed / terminal, 4) if terminal else None,
        "totalRecordedClaims": attempts_total,
        "meanAttemptsPerCompletedStory": (
            round(sum(completed_attempts) / len(completed_attempts), 3)
            if completed_attempts else None
        ),
        "failureCategories": dict(categories.most_common()),
        "failureStages": dict(stages.most_common()),
        "repeatedFailures": sorted(repeats, key=lambda item: -item["count"]),
        "failureRows": failure_rows,
        "eventCounts": dict(event_counter.most_common()),
        "logMarkers": dict(log_counter.most_common()),
        "checkpoints": checkpoint_summary,
        "limitations": [
            "Controller records may exclude archived or rotated production attempts.",
            "Log markers are occurrences, not a count of distinct failed jobs.",
            "Unrecorded duration and energy values are not estimated.",
            "A real before-and-after baseline requires production logs from both revisions.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, help="Command Center state JSON")
    parser.add_argument("--events", type=Path, action="append", default=[], help="Events JSONL")
    parser.add_argument("--log", type=Path, action="append", default=[], help="Worker log")
    parser.add_argument("--checkpoint", type=Path, action="append", default=[], help="Checkpoint JSON")
    parser.add_argument("--json", type=Path, help="Write aggregated report as JSON")
    parser.add_argument("--csv", type=Path, help="Write failure rows as CSV")
    args = parser.parse_args()
    if not (args.state or args.events or args.log or args.checkpoint):
        parser.error("provide at least one input; the tool never accesses live jobs automatically")
    state = read_json(args.state) if args.state else {}
    events = [row for path in args.events for row in records(path)]
    logs = [line for path in args.log
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines()]
    checkpoints = [read_json(path) for path in args.checkpoint]
    report = summarize(state, events, logs, checkpoints)
    print("AutoNewsRoller production failure report")
    print(f"Stories: {report['stories']}   Complete: {report['completed']}   Failed: {report['failed']}")
    print(f"Terminal success rate: {report['terminalSuccessRate'] if report['terminalSuccessRate'] is not None else 'unknown'}")
    print(f"Failure categories: {report['failureCategories']}")
    print(f"Failed stages: {report['failureStages']}")
    print(f"Repeated-failure stories: {len(report['repeatedFailures'])}")
    print(f"Checkpoint stage entries: {report['checkpoints']['recordedStageEntries']}")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with args.csv.open("w", encoding="utf-8", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=["storyId", "category", "stage", "time", "error"])
            writer.writeheader()
            writer.writerows(report["failureRows"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
