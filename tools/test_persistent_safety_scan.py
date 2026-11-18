#!/usr/bin/env python3
"""Resident pinned CPU scanner smoke on actual encoded MP4; does not test GPU."""
import hashlib, json, subprocess, sys, tempfile
from pathlib import Path
from test_content_safety_scan import make_video,run

def check(script,video,reference):
    sha=hashlib.sha256(video.read_bytes()).hexdigest()
    commands=[
      {"id":"one","operation":"video","path":str(video),"sha256":sha},
      {"id":"two","operation":"video","path":str(video),"sha256":sha},
      {"id":"bad","operation":"video","path":str(video),"sha256":"0"*64},
      {"id":"shutdown","operation":"shutdown"}]
    proc=subprocess.Popen([sys.executable,str(script),"--serve"],
                          stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE,text=True)
    try:out,err=proc.communicate("".join(json.dumps(x)+"\n" for x in commands),timeout=450)
    except subprocess.TimeoutExpired:
        proc.kill();proc.communicate()
        raise AssertionError("resident scanner hung")
    if proc.returncode:raise AssertionError("resident scanner failed: "+err[-800:])
    events=[json.loads(line) for line in out.splitlines()]
    ready=events[0]
    assert ready["event"]=="ready" and ready["backend"]=="cpu" and ready["provider"]=="CPU"
    results={m["id"]:m["result"] for m in events if m.get("event")=="result" and
             isinstance(m.get("result"),dict)}
    for id in ("one","two"):
        for field in ("status","detector","version","framesDecoded","framesExpected",
                      "framesInspected","disclosureFramesVerified","detections"):
            if field in reference:assert results[id][field]==reference[field],(id,field)
        assert results[id]["scannerPid"]==ready["pid"]
    assert results["bad"]["status"]=="SCANNER_UNAVAILABLE"
    assert events[-1]["status"]=="STOPPED"

def main():
    with tempfile.TemporaryDirectory(prefix="anr-resident-safety-") as folder:
        video=Path(folder,"disclosed.mp4")
        make_video(video,True)
        for name in ("content_safety_scan.py","content_context_scan.py"):
            script=Path(__file__).with_name(name)
            check(script,video,run(script,video))
    print("SafetyResidentSmoke PASS: full encoded MP4 parity; persistent PID")

if __name__=="__main__":main()
