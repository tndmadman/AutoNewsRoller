#!/usr/bin/env python3
"""Local NDJSON protocol tests with no detector imports or GPU claims."""
import hashlib
import io
import json
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import safety_scanner_ipc as ipc

class In(io.BytesIO):
    @property
    def buffer(self):
        return self

def once(requests, runner):
    old_in, old_out = sys.stdin, sys.stdout
    try:
        sys.stdin = In(b"".join(json.dumps(x).encode()+b"\n" for x in requests))
        sys.stdout = io.StringIO()
        ipc.serve("NudeNet", "3.4.2", runner, 0.2)
        return [json.loads(x) for x in sys.stdout.getvalue().splitlines()]
    finally:
        sys.stdin, sys.stdout = old_in, old_out

def test():
    with tempfile.TemporaryDirectory() as d:
        path = str(Path(d,"sample.bin").resolve())
        Path(path).write_bytes(b"original input")
        sha = ipc.file_hash(path)
        assert sha == hashlib.sha256(b"original input").hexdigest()
        events = once([
            {"id":"a1","operation":"image","path":path,"sha256":sha},
            {"id":"a2","operation":"video","path":path,"sha256":"0"*64},
            {"id":"a3","operation":"video","path":path,"sha256":sha},
            {"id":"a4","operation":"shutdown"}],
            lambda kind, name, progress: _runner(kind,name,progress))
        assert events[0]["event"] == "ready" and events[0]["backend"] == "cpu"
        assert events[1]["result"]["status"] == "UNSAFE"
        assert events[2]["result"]["status"] == "SCANNER_UNAVAILABLE"
        assert events[3]["event"] == "progress" and events[3]["framesProcessed"] == 2
        assert events[4]["result"]["status"] == "REVIEW_REQUIRED"
        assert events[5]["status"] == "STOPPED"
        def mutate(kind,name,progress):
            Path(name).write_bytes(b"mutated input")
            return {"status":"SAFE","detector":"NudeNet","version":"3.4.2"}
        denied = once([{"id":"a5","operation":"image","path":path,"sha256":sha}],mutate)
        assert denied[1]["result"]["status"] == "SCANNER_UNAVAILABLE"
        oversized=once([{"id":"a6","operation":"image","path":"x"*17000,
                         "sha256":sha}],_runner)
        assert oversized[1]["result"]["status"] == "SCANNER_UNAVAILABLE"
    print("SafetyScannerProtocolTest PASS (resident reuse, ids, frames, hashes, fail-closed)")

def _runner(kind,path,progress):
    if kind=="video":
        progress(2,2)
    return {"status":"UNSAFE" if kind=="image" else "REVIEW_REQUIRED",
            "detector":"NudeNet","version":"3.4.2"}

if __name__ == "__main__":
    test()
