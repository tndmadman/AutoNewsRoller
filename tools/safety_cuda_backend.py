#!/usr/bin/env python3
"""Experimental, parity-gated CUDA inference for the *pinned* safety models.

Never treats a provider listing as CUDA proof. The synthetic probe establishes
CUDA node execution only; a separate licensed-corpus parity report is required
before this backend can serve any user media.
"""
import hashlib
import importlib.resources
import json
import math
from pathlib import Path
import re
import subprocess

SHA = re.compile(r"^[0-9a-f]{64}$")

def hash_file(path):
    digest=hashlib.sha256()
    with open(path,"rb") as stream:
        for chunk in iter(lambda: stream.read(131072),b""):
            digest.update(chunk)
    return digest.hexdigest()

def vram_available(device, reserve_mb):
    if not (0<=device<=15 and 1024<=reserve_mb<=16384):
        raise ValueError("invalid CUDA device or free-VRAM reservation")
    result=subprocess.run(
        ["nvidia-smi",f"--id={device}","--query-gpu=memory.free",
         "--format=csv,noheader,nounits"],
        capture_output=True,text=True,timeout=7,check=True)
    try:
        free_mb=int(result.stdout.strip().splitlines()[0].split()[0])
    except (ValueError,IndexError) as invalid:
        raise RuntimeError("nvidia-smi did not report available VRAM") from invalid
    if free_mb<reserve_mb:
        raise RuntimeError(f"GPU VRAM reserve not met: free={free_mb}MiB reserve={reserve_mb}MiB")
    return free_mb

def _verified_cuda_session(path,device,probe):
    import onnxruntime as ort
    if "CUDAExecutionProvider" not in ort.get_available_providers():
        raise RuntimeError("CUDA EP not installed")
    options=ort.SessionOptions()
    options.enable_profiling=True
    options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session=ort.InferenceSession(str(path),sess_options=options,
        providers=[("CUDAExecutionProvider",{"device_id":device}),"CPUExecutionProvider"])
    session.disable_fallback()
    if "CUDAExecutionProvider" not in session.get_providers():
        raise RuntimeError("CUDA EP not active in model session")
    try:
        probe(session)
    finally:
        trace=Path(session.end_profiling())
    try:
        events=json.loads(trace.read_text(encoding="utf-8"))
    finally:
        trace.unlink(missing_ok=True)
    node_providers=[e.get("args",{}).get("provider","") for e in events
                    if e.get("cat")=="Node" and isinstance(e.get("args"),dict)]
    cuda_nodes=sum(p=="CUDAExecutionProvider" for p in node_providers)
    if cuda_nodes<1:
        raise RuntimeError("no CUDAExecutionProvider inference nodes in actual ONNX trace")
    return session,cuda_nodes

def _manifest(path,detector,model_sha,device):
    if not path:
        raise RuntimeError("GPU parity manifest required before any production CUDA scan")
    m=json.loads(Path(path).read_text(encoding="utf-8"))
    if (m.get("schemaVersion")!=1 or m.get("status")!="PASS"
        or m.get("nudeNetDecisionFlips")!=0
        or m.get("contextThresholdFlips")!=0
        or m.get("verifiedDevice")!=device
        or m.get("fixturesEvaluated",0)<50
        or m.get("encodedVideoFramesEvaluated",0)<100
        or not m.get("licensedFixturesConfirmed",False)):
        raise RuntimeError("GPU parity release gate is not satisfied")
    key="nudeNetModelSha256" if detector=="NudeNet" else "openNsfwOnnxSha256"
    if m.get(key)!=model_sha or not SHA.fullmatch(model_sha):
        raise RuntimeError(detector+" model hash changed since parity validation")
    nodes=m.get("verifiedCudaNodes",{})
    if not isinstance(nodes,dict) or not isinstance(nodes.get(detector),int) or nodes[detector]<1:
        raise RuntimeError(detector+" CUDA node execution not validated")
    if not (SHA.fullmatch(str(m.get("openNsfwWeightsSha256",""))) and
            SHA.fullmatch(str(m.get("openNsfwOnnxSha256",""))) and
            SHA.fullmatch(str(m.get("nudeNetModelSha256","")))):
        raise RuntimeError("parity model/weights provenance incomplete")
    return m

def nudenet_cuda(device,manifest,reserve_mb):
    import numpy as np
    import nudenet
    from nudenet import NudeDetector
    weights=Path(importlib.resources.files("nudenet").joinpath("320n.onnx"))
    if not weights.is_file():
        raise RuntimeError("pinned packaged NudeNet 320n.onnx model not found")
    sha=hash_file(weights)
    _manifest(manifest,"NudeNet",sha,device)
    vram_available(device,reserve_mb)
    detector=NudeDetector(model_path=str(weights),
                          providers=[("CUDAExecutionProvider",{"device_id":device})])
    if not hasattr(detector,"onnx_session"):
        raise RuntimeError("NudeNet 3.4.2 ONNX session API differs; no safe CUDA injection")
    def warm(session):
        detector.onnx_session=session
        if not isinstance(detector.detect(np.zeros((320,320,3),dtype=np.uint8)),list):
            raise RuntimeError("NudeNet CUDA output invalid")
    session,nodes=_verified_cuda_session(weights,device,warm)
    detector.onnx_session=session
    return detector,{"backend":"cuda","provider":"CUDAExecutionProvider",
                     "modelSha256":sha,"cudaNodes":nodes,"deviceId":device}

class ContextCudaModel:
    def __init__(self,session):
        self.session=session
        self.name=session.get_inputs()[0].name
    def __call__(self,batch,training=False):
        import numpy as np
        value=np.asarray(batch,dtype=np.float32)
        if value.ndim!=4 or value.shape[1:]!=(224,224,3):
            raise ValueError("unexpected OpenNSFW2 YAHOO preprocessing shape")
        result=np.asarray(self.session.run(None,{self.name:value})[0])
        if result.shape!=(len(value),2) or not np.isfinite(result).all():
            raise ValueError("OpenNSFW2 CUDA output invalid")
        return result

def context_cuda(device,manifest,onnx_path,reserve_mb):
    import numpy as np
    if not onnx_path:
        raise RuntimeError("converted OpenNSFW2 ONNX path is required")
    model_path=Path(onnx_path).resolve(strict=True)
    sha=hash_file(model_path)
    _manifest(manifest,"OpenNSFW2",sha,device)
    vram_available(device,reserve_mb)
    def warm(session):
        inp=session.get_inputs()
        if len(inp)!=1 or inp[0].type!="tensor(float)":
            raise RuntimeError("converted OpenNSFW2 ONNX model input differs")
        data=np.zeros((1,224,224,3),dtype=np.float32)
        result=np.asarray(session.run(None,{inp[0].name:data})[0])
        if result.shape!=(1,2) or not np.isfinite(result).all():
            raise RuntimeError("converted OpenNSFW2 CUDA output shape invalid")
    session,nodes=_verified_cuda_session(model_path,device,warm)
    return ContextCudaModel(session),{"backend":"cuda",
             "provider":"CUDAExecutionProvider","modelSha256":sha,
             "cudaNodes":nodes,"deviceId":device}
