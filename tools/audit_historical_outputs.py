#!/usr/bin/env python3
"""Fail-closed local archive inventory and video review. Never issues export approval.

Default: read all historical MP4s without modifying or deleting them, scan with
both mandatory pinned models, and save durable JSON/CSV findings. This is not
a TikTok API client and cannot remove platform strikes.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

POLICY = "content-safety-2026-10-28-v3"
MODELS = {"NudeNet": "3.4.2", "OpenNSFW2": "0.19.0"}
SCANNERS = (
    ("NudeNet", "tools/content_safety_scan.py"),
    ("OpenNSFW2", "tools/content_context_scan.py"),
)
STATES = ("SAFE", "UNSAFE", "REVIEW_REQUIRED")
CSV_FIELDS = (
    "file", "status", "reviewStatus", "mustNotRepost", "framesInspected",
    "disclosureVerified", "NudeNet", "OpenNSFW2", "sha256", "reason",
    "quarantinedAt", "fromCache",
)


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_name(path.name + "." + str(os.getpid()) + ".pending")
    try:
        pending.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(pending, path)
    finally:
        pending.unlink(missing_ok=True)


def inventory(root, extra_dirs=()):
    """Include all historical MP4s under output, not only final_videos.

    Exclude audit reports/quarantine so a second audit never re-ingests
    already quarantined media. Symlinks are skipped rather than followed.
    """
    root = Path(root).resolve()  # Normalize Windows short/long temporary paths.
    output = root / "output"
    dirs = [output] + [Path(x).expanduser().resolve() for x in extra_dirs]
    discovered = {}
    for directory in dirs:
        if not directory.is_dir():
            continue
        for file in directory.rglob("*"):
            if file.suffix.lower() != ".mp4" or not file.is_file() or file.is_symlink():
                continue
            full = file.resolve()
            if full.is_relative_to(output / "content_safety"):
                continue
            discovered[str(full).casefold() if os.name == "nt" else str(full)] = full
    return sorted(discovered.values(), key=lambda p: str(p).lower())


def source_key(root, path):
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def call_scanner(root, path, script):
    process = subprocess.run(
        [sys.executable, str(root / script), "--video", str(path)],
        timeout=7200, capture_output=True, text=True, check=False,
    )
    if process.returncode:
        raise RuntimeError(script + " failed (exit " + str(process.returncode) +
                           "): " + process.stderr.strip()[-300:])
    try:
        parsed = json.loads(process.stdout)
    except (ValueError, TypeError) as error:
        raise RuntimeError("invalid scanner JSON: " + script) from error
    if not isinstance(parsed, dict):
        raise RuntimeError("scanner returned non-object JSON: " + script)
    return parsed


def validate_scan(model, audit):
    if audit.get("detector") != model or audit.get("version") != MODELS[model]:
        raise ValueError(model + " scanner identity/version mismatch")
    if audit.get("status") not in STATES:
        raise ValueError(model + " returned invalid scan status")
    for field in ("framesInspected", "framesDecoded", "framesExpected"):
        v = audit.get(field)
        if type(v) is not int or v < 1:
            raise ValueError(model + " missing " + field)
    frames = audit["framesInspected"]
    if frames != audit["framesDecoded"] or frames != audit["framesExpected"]:
        raise ValueError(model + " incomplete final-frame coverage")
    if model == "NudeNet":
        if audit.get("coverage") != "every decoded frame; full and three overlapping crops":
            raise ValueError("NudeNet missing crop coverage")
        if audit.get("threshold") != 0.25:
            raise ValueError("NudeNet unrecognized threshold")
        disclosure = audit.get("disclosureFramesVerified")
        if type(disclosure) is not int or disclosure < 0 or disclosure > frames:
            raise ValueError("missing disclosure frame count")
        if (audit.get("disclosureVerified") is True) != (disclosure == frames):
            raise ValueError("inconsistent final-video AIGC disclosure evidence")
        if audit["status"] == "SAFE" and disclosure != frames:
            raise ValueError("NudeNet returned SAFE without disclosure")
    elif model == "OpenNSFW2":
        if audit.get("coverage") != "every decoded frame; complete frame; upper/lower crops on still images":
            raise ValueError("OpenNSFW2 missing coverage")
        if audit.get("reviewThreshold") != 0.20 or audit.get("hardBlockThreshold") != 0.70:
            raise ValueError("OpenNSFW2 unrecognized thresholds")
    return frames


def inspect(root, path):
    scans = {}
    for model, script in SCANNERS:
        result = call_scanner(root, path, script)
        validate_scan(model, result)
        scans[model] = result
    if scans["NudeNet"]["framesDecoded"] != scans["OpenNSFW2"]["framesDecoded"]:
        raise ValueError("independent scanners disagree on decoded frame count")
    statuses = [r["status"] for r in scans.values()]
    status = ("UNSAFE" if "UNSAFE" in statuses else
              "REVIEW_REQUIRED" if "REVIEW_REQUIRED" in statuses else "SAFE")
    return {
        "status": status,
        "reason": " / ".join(model + ": " + scans[model].get("reason", "no reason")
                             for model in MODELS),
        "framesInspected": scans["NudeNet"]["framesInspected"],
        "disclosureVerified": scans["NudeNet"]["disclosureVerified"],
        "modelVersions": dict(MODELS),
        "modelStatuses": {model: scans[model]["status"] for model in MODELS},
        "modelCoverage": {model: scans[model]["framesInspected"] for model in MODELS},
        "detections": {model: scans[model].get("detections", []) for model in MODELS},
        "detectionsTotal": {model: scans[model].get("detectionsTotal",
                              len(scans[model].get("detections", []))) for model in MODELS},
        "modelElapsedSeconds": {model: scans[model].get("elapsedSeconds", 0) for model in MODELS},
        "auditVerified": True,
    }


def usable_cache(prior, hash_before):
    return (
        isinstance(prior, dict) and prior.get("sha256") == hash_before
        and prior.get("policyVersion") == POLICY
        and prior.get("modelVersions") == MODELS
        and prior.get("auditVerified") is True
        and prior.get("status") in STATES
        and isinstance(prior.get("modelStatuses"), dict)
        and set(prior["modelStatuses"]) == set(MODELS)
        and all(status in STATES for status in prior["modelStatuses"].values())
        and isinstance(prior.get("framesInspected"), int)
        and prior["framesInspected"] > 0
        and isinstance(prior.get("modelCoverage"), dict)
        and all(prior["modelCoverage"].get(model) == prior["framesInspected"] for model in MODELS)
        and prior.get("disclosureVerified") in (True, False)
    )


def review_status(status):
    return {
        "SAFE": "TECHNICAL_SCREEN_ONLY_NOT_CLEARED_FOR_REPOST",
        "UNSAFE": "FLAGGED_UNSAFE_DO_NOT_POST",
        "REVIEW_REQUIRED": "REVIEW_REQUIRED_DO_NOT_POST",
        "SCANNER_UNAVAILABLE": "INCOMPLETE_AUDIT_DO_NOT_POST",
    }[status]


def quarantine(root, archive, path, digest):
    """Move only a currently rescanned UNSAFE file; never delete evidence."""
    key = source_key(root, path)
    safe_name = hashlib.sha256(key.encode("utf-8")).hexdigest()[:16] + "_" + path.name
    location = archive / "quarantine" / safe_name
    location.parent.mkdir(parents=True, exist_ok=True)
    if location.exists():
        location = location.with_name(location.stem + "_" + digest[:8] + location.suffix)
    shutil.move(str(path), str(location))
    sidecar = Path(str(path) + ".json")
    if sidecar.is_file():
        shutil.move(str(sidecar), str(Path(str(location) + ".json")))
    return source_key(root, location)


def save_csv(path, entries):
    pending = path.with_name(path.name + "." + str(os.getpid()) + ".pending")
    try:
        with pending.open("w", newline="", encoding="utf-8-sig") as stream:
            writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS)
            writer.writeheader()
            for entry in entries:
                row = {k: entry.get(k, "") for k in CSV_FIELDS}
                for model in MODELS:
                    row[model] = entry.get("modelStatuses", {}).get(model, "")
                # Prevent formulas executing when reports are opened in Excel.
                for key, value in row.items():
                    if isinstance(value, str) and value.startswith(("=", "+", "-", "@")):
                        row[key] = "'" + value
                writer.writerow(row)
        os.replace(pending, path)
    finally:
        pending.unlink(missing_ok=True)


def summarize(entries, started):
    counts = {"SAFE": 0, "UNSAFE": 0, "REVIEW_REQUIRED": 0, "SCANNER_UNAVAILABLE": 0}
    for entry in entries:
        counts[entry["status"]] += 1
    return {
        "status": ("NO_VIDEO_FILES" if not entries else
                   "INCOMPLETE" if counts["SCANNER_UNAVAILABLE"] else
                   "AUDIT_COMPLETE_WITH_FINDINGS" if counts["UNSAFE"] or counts["REVIEW_REQUIRED"] else
                   "AUDIT_COMPLETE_TECHNICAL_ONLY"),
        "policyVersion": POLICY,
        "modelVersions": dict(MODELS),
        "startedAt": started,
        "finishedAt": utcnow(),
        "total": len(entries),
        "counts": counts,
        "fullyScanned": sum(1 for e in entries if e.get("auditVerified")),
        "fromCache": sum(bool(e.get("fromCache")) for e in entries),
        "quarantined": sum(bool(e.get("quarantinedAt")) for e in entries),
        "mustNotRepost": len(entries),
        "note": "No archived result grants publish permission. Platform-side AIGC labeling is manual.",
        "files": entries,
    }


def audit(root, extra_dirs=(), resume=False, quarantine_flagged=False, progress=print):
    root = Path(root).resolve()
    archive = root / "output" / "content_safety"
    archive.mkdir(parents=True, exist_ok=True)
    previous = archive / "archive_index.json"
    try:
        cache = json.loads(previous.read_text(encoding="utf-8")) if previous.exists() else {}
        if not isinstance(cache, dict):
            cache = {}
    except (ValueError, OSError):
        cache = {}

    started = utcnow()
    paths = inventory(root, extra_dirs)
    entries = []
    index = {}
    for position, path in enumerate(paths, start=1):
        key = source_key(root, path)
        before = sha256(path)
        prior = cache.get(key, {})
        from_cache = resume and usable_cache(prior, before)
        # Never allow an old cached detector hit to move a file.
        if quarantine_flagged and prior.get("status") == "UNSAFE":
            from_cache = False
        if from_cache:
            finding = dict(prior)
        else:
            try:
                finding = inspect(root, path)
                if sha256(path) != before:
                    raise RuntimeError("video changed during audit")
            except Exception as error:
                finding = {"status": "SCANNER_UNAVAILABLE",
                           "reason": str(error)[:400], "auditVerified": False,
                           "modelVersions": dict(MODELS)}
        finding.update({
            "file": key, "sha256": before, "policyVersion": POLICY,
            "mustNotRepost": True, "fromCache": bool(from_cache),
            "reviewStatus": review_status(finding["status"]),
            "auditedAt": utcnow(), "sizeBytes": path.stat().st_size,
        })
        if finding["status"] == "UNSAFE" and quarantine_flagged:
            try:
                if from_cache:
                    raise RuntimeError("quarantine cannot rely on cached results")
                finding["quarantinedAt"] = quarantine(root, archive, path, before)
            except Exception as error:
                finding["quarantineError"] = str(error)[:400]
        entries.append(finding)
        index[key] = finding
        atomic_json(previous, index)
        progress(f"[{position}/{len(paths)}] {finding['status']}: {key}")

    report = summarize(entries, started)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    report_path = archive / ("archive-audit-" + stamp + ".json")
    csv_path = archive / ("archive-audit-" + stamp + ".csv")
    report["reportPath"] = str(report_path)
    report["csvPath"] = str(csv_path)
    atomic_json(report_path, report)
    save_csv(csv_path, entries)
    atomic_json(archive / "archive-audit-latest.json", report)
    progress("STATUS " + report["status"] + " | TOTAL " + str(report["total"]) +
             " | UNSAFE " + str(report["counts"]["UNSAFE"]) +
             " | REVIEW " + str(report["counts"]["REVIEW_REQUIRED"]) +
             " | ERROR " + str(report["counts"]["SCANNER_UNAVAILABLE"]))
    progress("JSON " + str(report_path))
    progress("CSV  " + str(csv_path))
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".", help="AutoNewsRoller project root")
    parser.add_argument("--extra-dir", action="append", default=[],
                        help="Also audit MP4s in this additional directory (repeatable)")
    parser.add_argument("--resume", action="store_true",
                        help="Reuse matching full-model, SHA-bound previous audit results")
    parser.add_argument("--quarantine-flagged", "--quarantine-confirmed-unsafe",
                        dest="quarantine_flagged", action="store_true",
                        help="Explicit opt-in: move detector-flagged unsafe files, never delete them")
    args = parser.parse_args()
    report = audit(args.root, args.extra_dir, args.resume, args.quarantine_flagged)
    if report["status"] == "NO_VIDEO_FILES":
        print("No MP4s found. Historical video audit NOT performed.", file=sys.stderr)
        return 3
    if report["status"] == "INCOMPLETE":
        print("One or more videos were not scanned by BOTH required models.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
