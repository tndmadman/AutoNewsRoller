#!/usr/bin/env python3
"""Independent pornographic-content context review using OpenNSFW2.

Requires the separately provisioned Keras/TensorFlow CPU environment and model
weights. A missing dependency or weight file is an error, never SAFE.
Thresholds are conservative starting values, not calibrated guarantees.
"""
import argparse
from contextlib import redirect_stdout
import importlib.metadata
import json
import os
import math
import sys
import time

MODEL_VERSION = "0.19.0"
HARD_BLOCK = 0.70
REVIEW = 0.20
BATCH = 8


def classify_scores(values, start, evidence):
    import numpy as np
    scores = np.asarray(values)
    if scores.ndim != 2 or scores.shape[1] != 2:
        raise ValueError("invalid context model output shape")
    for offset, row in enumerate(scores):
        probability = float(row[1])
        if not math.isfinite(probability) or not 0 <= probability <= 1:
            raise ValueError("invalid context model probability")
        if probability >= REVIEW:
            evidence.append({
                "frame": start + offset, "probability": round(probability, 5),
                "decision": "UNSAFE" if probability >= HARD_BLOCK else "REVIEW_REQUIRED",
            })
    return len(scores)


def run_scan(kind, path, model, n2, progress=None):
    import cv2
    import numpy as np
    from PIL import Image
    started = time.monotonic()
    preprocess_wall = infer_wall = decode_wall = 0.0
    evidence = []
    frames = expected = 0

    def prepare(frame):
        nonlocal preprocess_wall
        start = time.monotonic()
        if frame is None or len(frame.shape) != 3 or frame.shape[2] != 3:
            raise ValueError("invalid BGR frame")
        image = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        value = n2.preprocess_image(image, n2.Preprocessing.YAHOO)
        preprocess_wall += time.monotonic() - start
        return value

    def infer(batch):
        nonlocal infer_wall
        start = time.monotonic()
        with redirect_stdout(sys.stderr):
            result = np.asarray(model(np.asarray(batch), training=False))
        infer_wall += time.monotonic() - start
        return result

    if kind == "image":
        frame = cv2.imread(path, cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError("unreadable image")
        h, w = frame.shape[:2]
        regions = [frame]
        if h > 256 and w > 256:
            regions.extend((frame[:5*h//8, :], frame[3*h//8:, :]))
        for region in regions:
            classify_scores(infer([prepare(region)]), 0, evidence)
        frames = expected = 1
    else:
        cap = cv2.VideoCapture(path)
        if not cap.isOpened():
            raise ValueError("unreadable video")
        expected = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        queue = []
        try:
            while True:
                t = time.monotonic()
                ok, frame = cap.read()
                decode_wall += time.monotonic()-t
                if not ok:
                    break
                queue.append(prepare(frame))
                frames += 1
                if len(queue) >= BATCH:
                    classify_scores(infer(queue), frames-len(queue), evidence)
                    queue.clear()
                    if progress is not None:
                        progress(frames, expected)
            if queue:
                classify_scores(infer(queue), frames-len(queue), evidence)
                queue.clear()
        finally:
            cap.release()
        if expected < 1 or frames != expected:
            raise ValueError(f"incomplete context video decode: expected={expected}, actual={frames}")
    if progress is not None:
        progress(frames, expected)
    unsafe = any(x["decision"] == "UNSAFE" for x in evidence)
    review = bool(evidence)
    return {
        "status": "UNSAFE" if unsafe else ("REVIEW_REQUIRED" if review else "SAFE"),
        "reason": "context model detected high sexual-content probability" if unsafe else
                  ("sexual-content context requires review" if review else "no above-threshold sexual context"),
        "detector": "OpenNSFW2", "version": MODEL_VERSION,
        "reviewThreshold": REVIEW, "hardBlockThreshold": HARD_BLOCK,
        "framesInspected": frames, "framesDecoded": frames, "framesExpected": expected,
        "coverage": "every decoded frame; complete frame; upper/lower crops on still images",
        "detections": evidence[:40], "detectionsTotal": len(evidence),
        "decodeWallSeconds": round(decode_wall, 3),
        "preprocessWallSeconds": round(preprocess_wall, 3),
        "inferWallSeconds": round(infer_wall, 3),
        "elapsedSeconds": round(time.monotonic()-started, 3),
    }

def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--health", action="store_true")
    mode.add_argument("--image")
    mode.add_argument("--video")
    mode.add_argument("--serve", action="store_true")
    parser.add_argument("--backend", choices=("cpu","cuda"), default="cpu")
    parser.add_argument("--cuda-device", type=int, default=0)
    parser.add_argument("--parity-manifest")
    parser.add_argument("--onnx-model")
    parser.add_argument("--vram-reserve-mb", type=int, default=4096)
    args = parser.parse_args()
    import cv2
    import numpy as np
    import opennsfw2 as n2
    if importlib.metadata.version("opennsfw2") != MODEL_VERSION:
        raise RuntimeError("unvalidated OpenNSFW2 model version")
    loading = time.monotonic()
    metadata={"backend":"cpu","provider":"CPU"}
    if args.backend=="cuda":
        from safety_cuda_backend import context_cuda
        model,metadata=context_cuda(args.cuda_device,args.parity_manifest,
                                    args.onnx_model,args.vram_reserve_mb)
    else:
        with redirect_stdout(sys.stderr):
            model = n2.make_open_nsfw_model()
    startup_seconds = time.monotonic()-loading
    if args.health:
        from PIL import Image
        probe = Image.fromarray(np.zeros((256, 256, 3), dtype=np.uint8))
        with redirect_stdout(sys.stderr):
            scores = np.asarray(model(np.asarray(
                [n2.preprocess_image(probe, n2.Preprocessing.YAHOO)]), training=False))
        if scores.shape != (1, 2) or not np.isfinite(scores).all():
            raise ValueError("context model failed health inference")
        print(json.dumps({"status":"READY", "detector":"OpenNSFW2", "version":MODEL_VERSION,
                          "policyVersion":"content-safety-2026-10-28-v3",
                          "test":"model weights loaded and inference completed"}))
    elif args.serve:
        from safety_scanner_ipc import serve
        serve("OpenNSFW2", MODEL_VERSION, lambda kind, path, callback:
              run_scan(kind, path, model, n2, callback), startup_seconds, metadata)
    else:
        kind = "image" if args.image else "video"
        result=run_scan(kind,args.image or args.video,model,n2)
        result.update(metadata)
        print(json.dumps(result,separators=(",", ":")))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("CONTENT_CONTEXT_SCANNER_UNAVAILABLE: " + str(error), file=sys.stderr)
        sys.exit(2)
