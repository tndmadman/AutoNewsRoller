#!/usr/bin/env python3
"""Independent pornographic-content context review using OpenNSFW2.

Requires a separately provisioned Keras/TensorFlow CPU environment and model
weights. A missing dependency or weight file is an error, never SAFE.
Thresholds are conservative starting values, not calibrated guarantees.
"""
import argparse
from contextlib import redirect_stdout
import importlib.metadata
import json
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


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--health", action="store_true")
    mode.add_argument("--image")
    mode.add_argument("--video")
    args = parser.parse_args()

    import cv2
    import numpy as np
    from PIL import Image
    import opennsfw2 as n2

    if importlib.metadata.version("opennsfw2") != MODEL_VERSION:
        raise RuntimeError("unvalidated OpenNSFW2 model version")
    start = time.monotonic()
    # The weight downloader prints to stdout; only the structured JSON
    # protocol is permitted on stdout.
    with redirect_stdout(sys.stderr):
        model = n2.make_open_nsfw_model()

    def prepare(frame):
        if frame is None or len(frame.shape) != 3 or frame.shape[2] != 3:
            raise ValueError("invalid BGR frame")
        image = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        return n2.preprocess_image(image, n2.Preprocessing.YAHOO)

    def infer(batch):
        with redirect_stdout(sys.stderr):
            return np.asarray(model(np.asarray(batch), training=False))

    if args.health:
        result = infer([prepare(np.zeros((256, 256, 3), dtype=np.uint8))])
        if result.shape != (1, 2) or not np.isfinite(result).all():
            raise ValueError("context model failed health inference")
        print(json.dumps({"status":"READY", "detector":"OpenNSFW2",
                          "version":MODEL_VERSION, "policyVersion":"content-safety-2026-10-28-v3",
                          "test":"model weights loaded and inference completed"}))
        return

    evidence = []
    frames = 0
    expected = 0
    if args.image:
        frame = cv2.imread(args.image, cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError("unreadable image")
        # Inspect the complete still and enlarged upper/lower crops.
        h, w = frame.shape[:2]
        regions = [frame]
        if h > 256 and w > 256:
            regions.extend((frame[:5*h//8, :], frame[3*h//8:, :]))
        for region in regions:
            classify_scores(infer([prepare(region)]), 0, evidence)
        frames = expected = 1
    else:
        cap = cv2.VideoCapture(args.video)
        if not cap.isOpened():
            raise ValueError("unreadable video")
        expected = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        queue = []
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                queue.append(prepare(frame))
                frames += 1
                if len(queue) >= BATCH:
                    classify_scores(infer(queue), frames-len(queue), evidence)
                    queue.clear()
            if queue:
                classify_scores(infer(queue), frames-len(queue), evidence)
                queue.clear()
        finally:
            cap.release()
        if expected < 1 or frames != expected:
            raise ValueError(f"incomplete context video decode: expected={expected}, actual={frames}")

    unsafe = any(x["decision"] == "UNSAFE" for x in evidence)
    review = bool(evidence)
    print(json.dumps({
        "status":"UNSAFE" if unsafe else ("REVIEW_REQUIRED" if review else "SAFE"),
        "reason":"context model detected high sexual-content probability" if unsafe else
                 ("sexual-content context requires review" if review else "no above-threshold sexual context"),
        "detector":"OpenNSFW2", "version":MODEL_VERSION,
        "reviewThreshold":REVIEW, "hardBlockThreshold":HARD_BLOCK,
        "framesInspected":frames, "framesDecoded":frames, "framesExpected":expected,
        "coverage":"every decoded frame; complete frame; upper/lower crops on still images",
        "detections":evidence[:40], "detectionsTotal":len(evidence),
        "elapsedSeconds":round(time.monotonic()-start, 3),
    },separators=(",",":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("CONTENT_CONTEXT_SCANNER_UNAVAILABLE: " + str(error), file=sys.stderr)
        sys.exit(2)
