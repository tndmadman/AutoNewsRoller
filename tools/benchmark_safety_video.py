#!/usr/bin/env python3
"""Actual encoded video benchmark. Produces measured JSON; never fake metrics."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time

def run(command,timeout):
    started=time.perf_counter()
    p=subprocess.run(command,text=True,capture_output=True,timeout=timeout)
    elapsed=time.perf_counter()-started
    if p.returncode!=0:
        raise RuntimeError(f"scanner exit {p.returncode}: {p.stderr[-500:]}")
    value=json.loads(p.stdout.strip())
    if value.get("status") not in ("SAFE","UNSAFE","REVIEW_REQUIRED"):
        raise RuntimeError("non-decision scanner result")
    if any(value.get(key)!=value.get("framesDecoded") for key in
           ("framesInspected","framesExpected")):
        raise RuntimeError("benchmark detects incomplete video coverage")
    return value,elapsed

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--video",required=True,help="Real encoded MP4, with AI disclosure")
    p.add_argument("--python",default=sys.executable)
    p.add_argument("--backend",choices=["cpu","cuda"],required=True)
    p.add_argument("--parity-manifest")
    p.add_argument("--context-onnx")
    p.add_argument("--runs",type=int,default=5)
    p.add_argument("--out",required=True)
    args=p.parse_args()
    if args.runs<5:raise ValueError("minimum 5 runs for reproducibility")
    video=Path(args.video).resolve(strict=True)
    if video.suffix.lower()!=".mp4":raise ValueError("expected actual encoded MP4")
    if video.stat().st_size<10000:raise ValueError("video too small for meaningful benchmark")
    if args.backend=="cuda" and (not args.parity_manifest or not args.context_onnx):
        raise ValueError("CUDA bench needs passing parity manifest and converted ONNX model")
    digest=hashlib.sha256()
    with video.open("rb") as stream:
        for chunk in iter(lambda:stream.read(131072),b""):
            digest.update(chunk)
    record={"schemaVersion":1,"status":"MEASURED","videoSha256":
             digest.hexdigest(),
            "videoBytes":video.stat().st_size,"backendRequested":args.backend,
            "runs":args.runs,"modelResults":{},"machine":os.name}
    for detector,script in (("NudeNet","content_safety_scan.py"),
                            ("OpenNSFW2","content_context_scan.py")):
        samples=[]
        for i in range(args.runs):
            command=[args.python,str(Path(__file__).parent/script),"--video",str(video),
                     "--backend",args.backend]
            if args.backend=="cuda":
                command.extend(["--parity-manifest",args.parity_manifest])
                if detector=="OpenNSFW2":
                    command.extend(["--onnx-model",args.context_onnx])
            result,seconds=run(command,7200)
            if args.backend=="cuda" and (result.get("backend")!="cuda" or
                                        result.get("cudaNodes",0)<1):
                raise RuntimeError("CUDA execution unproven")
            samples.append({"wallSeconds":round(seconds,3),
                "backend":result.get("backend","cpu"),"provider":result.get("provider"),
                "frames":result["framesDecoded"],"status":result["status"],
                "modelAndPrepostWallSeconds":result.get("modelAndPrepostWallSeconds"),
                "inferWallSeconds":result.get("inferWallSeconds"),
                "decodeWallSeconds":result.get("decodeWallSeconds")})
        walls=[x["wallSeconds"] for x in samples]
        record["modelResults"][detector]={"samples":samples,
                    "medianWallSeconds":statistics.median(walls),
                    "p95WallSeconds":sorted(walls)[max(0,int(.95*(len(walls)-1)))],
                    "maxWallSeconds":max(walls)}
    Path(args.out).write_text(json.dumps(record,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(record))
if __name__=="__main__":
    try:main()
    except Exception as error:
        print("BENCHMARK_FAILED: "+str(error),file=sys.stderr);sys.exit(2)
