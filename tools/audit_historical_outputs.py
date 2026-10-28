#!/usr/bin/env python3
"""Optional read-only-by-default archival review. Never reaches the TikTok account."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

def sha256(path):
    digest=hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--root", default=".")
    p.add_argument("--quarantine-confirmed-unsafe", action="store_true",
                   help="Move detector-confirmed UNSAFE files to a restricted local quarantine (never delete)")
    args=p.parse_args()
    root=Path(args.root).resolve()
    archive=root/"output"/"content_safety"
    archive.mkdir(parents=True,exist_ok=True)
    paths=sorted(set(root.glob("output/**/final_videos/*.mp4")) |
                 set((root/"output/command_center/videos").glob("*.mp4")))
    previous=archive/"archive_index.json"
    cache=json.loads(previous.read_text("utf-8")) if previous.exists() else {}
    entries={}
    for path in paths:
        file_hash=sha256(path)
        key=str(path.relative_to(root))
        existing=cache.get(key,{})
        if existing.get("sha256")==file_hash and existing.get("policyVersion")=="content-safety-2026-10-28-v1" and existing.get("status") in ("SAFE","UNSAFE","REVIEW_REQUIRED"):
            finding=existing
        else:
            action=[sys.executable,str(root/"tools/content_safety_scan.py"),"--video",str(path)]
            try:
                process=subprocess.run(action,timeout=7200,capture_output=True,text=True)
                if process.returncode!=0:
                    finding={"status":"SCANNER_UNAVAILABLE","reason":process.stderr[-300:]}
                else:
                    result=json.loads(process.stdout)
                    finding={"status":result["status"],"reason":result["reason"],
                             "framesInspected":result["framesInspected"],
                             "detections":result.get("detections",[]),
                             "disclosureVerified":result.get("disclosureVerified",False),
                             "elapsedSeconds":result.get("elapsedSeconds",0)}
            except Exception as error:
                finding={"status":"SCANNER_UNAVAILABLE","reason":str(error)[:300]}
            finding.update({"sha256":file_hash,"policyVersion":"content-safety-2026-10-28-v1"})
        finding["file"]=key
        finding["mustNotRepost"]=finding["status"]!="SAFE"
        if finding["status"]=="UNSAFE" and args.quarantine_confirmed_unsafe:
            q=archive/"quarantine"/key.replace("/","__").replace("\\","__")
            q.parent.mkdir(parents=True,exist_ok=True)
            if q.exists():
                q=q.with_name(q.stem+"_"+file_hash[:12]+q.suffix)
            shutil.move(str(path),str(q))
            sidecar=Path(str(path)+".json")
            if sidecar.is_file():
                shutil.move(str(sidecar),str(Path(str(q)+".json")))
            finding["quarantinedAt"]=str(q.relative_to(root))
        entries[key]=finding
        print(finding["status"],key,file=sys.stderr)
    previous.write_text(json.dumps(entries,indent=2),encoding="utf-8")
    report=archive/("archive-audit-"+datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")+".json")
    report.write_text(json.dumps({"policyVersion":"content-safety-2026-10-28-v1",
                                  "scannedAt":datetime.now(timezone.utc).isoformat(),
                                  "total":len(entries),"files":list(entries.values())},indent=2),encoding="utf-8")
    print(str(report))
if __name__=="__main__":
    main()
