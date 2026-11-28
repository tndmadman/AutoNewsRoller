#!/usr/bin/env python3
"""Export the exact OpenNSFW2 0.19.0 Keras model and original cached weights.

Offline conversion is NOT a GPU rollout approval. No third-party ONNX weights.
Keras/TensorFlow/tf2onnx support varies: fail with a specific error and keep CPU.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import sys

def sha(path):
    h=hashlib.sha256()
    with open(path,"rb") as stream:
        for b in iter(lambda:stream.read(131072),b""):
            h.update(b)
    return h.hexdigest()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",required=True)
    args=parser.parse_args()
    import numpy as np
    import onnxruntime as ort
    import opennsfw2 as n2
    from opennsfw2._download import get_default_weights_path
    if importlib.metadata.version("opennsfw2")!="0.19.0":
        raise RuntimeError("unexpected OpenNSFW2 version")
    weights=Path(get_default_weights_path()).resolve()
    model=n2.make_open_nsfw_model()
    if not weights.is_file():
        raise RuntimeError("reference Keras weights are not present")
    source_sha=sha(weights)
    destination=Path(args.output).resolve()
    destination.parent.mkdir(parents=True,exist_ok=True)
    if destination.exists():
        raise RuntimeError("export destination exists; refuse to overwrite previous model")
    try:
        # Keras 3 supports ONNX model export through tf2onnx when compatible.
        import tensorflow as tf
        # Keras 3 requires a real call before exporting, even for a built model.
        warm=np.asarray(model(np.zeros((1,224,224,3),dtype=np.float32),training=False))
        if warm.shape!=(1,2) or not np.isfinite(warm).all():
            raise RuntimeError("reference model warmup returned invalid output")
        # Keep the batch dimension dynamic for the production eight-frame batches.
        model.export(str(destination),format="onnx",opset_version=17,
                     input_signature=[tf.TensorSpec((None,224,224,3),tf.float32)])
    except Exception as problem:
        destination.unlink(missing_ok=True)
        raise RuntimeError("Keras3 -> ONNX export unavailable for this pinned environment: "+
                           type(problem).__name__+": "+str(problem)[:500]) from problem
    # Export success alone says nothing about model parity. Check just gross
    # input/output shape and numerical agreement on reproducible synthetic probes.
    sess=ort.InferenceSession(str(destination),providers=["CPUExecutionProvider"])
    if len(sess.get_inputs())!=1 or sess.get_inputs()[0].type!="tensor(float)":
        raise RuntimeError("exported model input is not expected float32")
    rng=np.random.default_rng(1017)
    probes=rng.normal(size=(8,224,224,3)).astype(np.float32)
    keras=np.asarray(model(probes,training=False))
    exported=np.asarray(sess.run(None,{sess.get_inputs()[0].name:probes})[0])
    if keras.shape!=(8,2) or exported.shape!=(8,2):
        raise RuntimeError("export output shape differs from pinned model")
    diff=np.abs(keras-exported)
    if not np.isfinite(diff).all() or float(np.max(diff))>0.005:
        raise RuntimeError("synthetic CPU Keras vs ONNX numeric parity failed")
    manifest={"schemaVersion":1,"status":"UNVALIDATED","model":"OpenNSFW2",
              "modelVersion":"0.19.0","kerasWeightsSha256":source_sha,
              "onnxSha256":sha(destination),
              "onnxRuntimeVersion":ort.__version__,
              "kerasRuntimeVersion":importlib.metadata.version("keras"),
              "tensorFlowVersion":importlib.metadata.version("tensorflow-cpu"),
              "maxSyntheticDifference":float(np.max(diff)),
              "note":"UNVALIDATED until full licensed fixture parity and actual GPU CUDA node execution"}
    destination.with_suffix(destination.suffix+".export.json").write_text(
        json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(manifest,sort_keys=True))
if __name__=="__main__":
    try:main()
    except Exception as error:
        print("OPENNSFW2_EXPORT_BLOCKED: "+str(error),file=sys.stderr)
        sys.exit(2)
