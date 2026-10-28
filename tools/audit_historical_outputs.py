#!/usr/bin/env python3
"""Read-only-by-default local archive audit. Never grants posting approval."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

POLICY = "content-safety-2026-10-28-v3"
MODELS = {"NudeNet":"3.4.2", "OpenNSFW2":"0.19.0"}
SCANNERS = (("NudeNet", "tools/content_safety_scan.py"),
            ("OpenNSFW2", "tools/content_context_scan.py"))


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect(root, path):
    scans = {}
    for model, script in SCANNERS:
        result = subprocess.run(
            [sys.executable, str(root / script), "--video", str(path)],
            timeout=7200, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            raise RuntimeError(model + " unavailable: " + result.stderr[-250:])
        audit = json.loads(result.stdout)
        if audit.get("detector") != model or audit.get("version") != MODELS[model]:
            raise RuntimeError("unexpected " + model + " identity or version")
        count = audit.get("framesDecoded", 0)
        if (not isinstance(count, int) or count < 1 or
                audit.get("framesInspected") != count or audit.get("framesExpected") != count):
            raise RuntimeError(model + " did not inspect every video frame")
        scans[model] = audit

    if scans["NudeNet"]["framesDecoded"] != scans["OpenNSFW2"]["framesDecoded"]:
        raise RuntimeError("independent scanners disagree about frame coverage")
    statuses = [scan["status"] for scan in scans.values()]
    if any(status not in ("SAFE", "UNSAFE", "REVIEW_REQUIRED") for status in statuses):
        raise RuntimeError("unexpected detector status")
    status = ("UNSAFE" if "UNSAFE" in statuses else
              "REVIEW_REQUIRED" if "REVIEW_REQUIRED" in statuses else "SAFE")
    return {
        "status": status,
        "reason": " / ".join(model + ": " + scans[model]["reason"] for model, _ in SCANNERS),
        "framesInspected": scans["NudeNet"]["framesInspected"],
        "disclosureVerified": scans["NudeNet"]["disclosureVerified"],
        "modelVersions": MODELS,
        "modelStatuses": {model: scans[model]["status"] for model, _ in SCANNERS},
        "detections": {model: scans[model].get("detections", []) for model, _ in SCANNERS},
        "modelElapsedSeconds": {model: scans[model].get("elapsedSeconds", 0) for model, _ in SCANNERS},
        "auditVerified": True,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--quarantine-confirmed-unsafe", action="store_true",
                        help="Move scanner-confirmed unsafe files to local quarantine; never delete")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    archive = root / "output" / "content_safety"
    archive.mkdir(parents=True, exist_ok=True)
    paths = sorted(set(root.glob("output/**/final_videos/*.mp4")) |
                   set((root / "output/command_center/videos").glob("*.mp4")))
    previous = archive / "archive_index.json"
    cache = json.loads(previous.read_text("utf-8")) if previous.exists() else {}
    entries = {}

    for path in paths:
        key = str(path.relative_to(root))
        before = sha256(path)
        prior = cache.get(key, {})
        eligible = (prior.get("sha256") == before and
                    prior.get("policyVersion") == POLICY and
                    prior.get("modelVersions") == MODELS and
                    prior.get("auditVerified") is True and
                    prior.get("status") in ("SAFE", "UNSAFE", "REVIEW_REQUIRED") and
                    set(prior.get("modelStatuses", {})) == set(MODELS))
        if eligible:
            finding = dict(prior)
        else:
            try:
                finding = inspect(root, path)
                if sha256(path) != before:
                    raise RuntimeError("archive file changed while scanning")
            except Exception as error:
                finding = {"status":"SCANNER_UNAVAILABLE", "reason":str(error)[:300],
                           "auditVerified":False, "modelVersions":MODELS}
        finding.update({"sha256":before, "policyVersion":POLICY, "file":key})
        # Archival SAFE is informational only. Publishing requires a separate
        # hash-bound current production approval, not an archive verdict.
        finding["mustNotRepost"] = True

        if finding["status"] == "UNSAFE" and args.quarantine_confirmed_unsafe:
            q = archive / "quarantine" / key.replace("/", "__").replace("\\", "__")
            q.parent.mkdir(parents=True, exist_ok=True)
            if q.exists():
                q = q.with_name(q.stem + "_" + before[:12] + q.suffix)
            shutil.move(str(path), str(q))
            sidecar = Path(str(path) + ".json")
            if sidecar.is_file():
                shutil.move(str(sidecar), str(Path(str(q) + ".json")))
            finding["quarantinedAt"] = str(q.relative_to(root))
        entries[key] = finding
        print(finding["status"], key, file=sys.stderr)

    tmp = archive / "archive_index.json.pending"
    tmp.write_text(json.dumps(entries, indent=2), encoding="utf-8")
    os.replace(tmp, previous)
    report = archive / ("archive-audit-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
    report.write_text(json.dumps({
        "policyVersion":POLICY, "scannedAt":datetime.now(timezone.utc).isoformat(),
        "total":len(entries), "files":list(entries.values()),
        "notice":"Archive scan does not grant permission or controller approval to repost",
    }, indent=2), encoding="utf-8")
    print(str(report))


if __name__ == "__main__":
    main()
