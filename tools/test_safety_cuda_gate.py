#!/usr/bin/env python3
"""Pure standard library denial tests for CUDA rollout manifest checks."""
import json
import tempfile
from pathlib import Path
import safety_cuda_backend as cuda

def denied(path,detector,sha):
    try:cuda._manifest(path,detector,sha,0)
    except (RuntimeError,ValueError,FileNotFoundError):return
    raise AssertionError("invalid CUDA manifest accepted")

def main():
    with tempfile.TemporaryDirectory() as dir:
        manifest=Path(dir,"parity.json")
        sha="a"*64
        denied(None,"NudeNet",sha)
        base={"schemaVersion":1,"status":"UNVALIDATED",
              "nudeNetDecisionFlips":0,"contextThresholdFlips":0,
              "verifiedDevice":0,"fixturesEvaluated":51,
              "encodedVideoFramesEvaluated":110,"licensedFixturesConfirmed":True,
              "nudeNetModelSha256":sha,"openNsfwOnnxSha256":sha,
              "openNsfwWeightsSha256":sha,
              "verifiedCudaNodes":{"NudeNet":3,"OpenNSFW2":4}}
        manifest.write_text(json.dumps(base),encoding="utf-8")
        denied(manifest,"NudeNet",sha)
        base["status"]="PASS"
        base["nudeNetDecisionFlips"]=1
        manifest.write_text(json.dumps(base),encoding="utf-8")
        denied(manifest,"NudeNet",sha)
        base["nudeNetDecisionFlips"]=0
        base["verifiedCudaNodes"]["NudeNet"]=0
        manifest.write_text(json.dumps(base),encoding="utf-8")
        denied(manifest,"NudeNet",sha)
        base["verifiedCudaNodes"]["NudeNet"]=3
        base["fixturesEvaluated"]=3
        manifest.write_text(json.dumps(base),encoding="utf-8")
        denied(manifest,"NudeNet",sha)
        base["fixturesEvaluated"]=51
        manifest.write_text(json.dumps(base),encoding="utf-8")
        assert cuda._manifest(manifest,"NudeNet",sha,0)["status"]=="PASS"
        denied(manifest,"OpenNSFW2","b"*64)
    print("SafetyCudaGateTest PASS (no false GPU activation without parity)")
if __name__=="__main__":main()
