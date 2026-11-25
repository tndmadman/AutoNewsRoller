#!/usr/bin/env python3
"""GPU readiness manifest denial tests; no physical GPU is used."""
import json,tempfile
from pathlib import Path
import safety_cuda_backend as cuda
def denied(path,model,sha):
    try:cuda._manifest(path,model,sha,0)
    except (RuntimeError,ValueError,FileNotFoundError):return
    raise AssertionError("invalid CUDA manifest accepted")
def main():
    with tempfile.TemporaryDirectory() as d:
        p=Path(d,"parity.json");sha="a"*64
        denied(None,"NudeNet",sha)
        manifest={"schemaVersion":1,"status":"UNVALIDATED","ortVersion":"1.20.2",
            "nudeNetDecisionFlips":0,"contextThresholdFlips":0,
            "verifiedDevice":0,"fixturesEvaluated":51,
            "encodedVideoFramesEvaluated":110,"licensedFixturesConfirmed":True,
            "nudeNetModelSha256":sha,"openNsfwOnnxSha256":sha,
            "openNsfwWeightsSha256":sha,
            "verifiedCudaNodes":{"NudeNet":3,"OpenNSFW2":4}}
        p.write_text(json.dumps(manifest))
        denied(p,"NudeNet",sha)
        manifest["status"]="PASS"
        manifest["contextThresholdFlips"]=1
        p.write_text(json.dumps(manifest))
        denied(p,"NudeNet",sha)
        manifest["contextThresholdFlips"]=0
        manifest["verifiedCudaNodes"]["NudeNet"]=0
        p.write_text(json.dumps(manifest))
        denied(p,"NudeNet",sha)
        manifest["verifiedCudaNodes"]["NudeNet"]=3
        manifest["fixturesEvaluated"]=10
        p.write_text(json.dumps(manifest))
        denied(p,"NudeNet",sha)
        manifest["fixturesEvaluated"]=51
        p.write_text(json.dumps(manifest))
        assert cuda._manifest(p,"NudeNet",sha,0)["status"]=="PASS"
        denied(p,"OpenNSFW2","b"*64)
        manifest["ortVersion"]="1.20.1"
        p.write_text(json.dumps(manifest))
        denied(p,"NudeNet",sha)
    print("SafetyCudaGateTest PASS")
if __name__=="__main__":main()
