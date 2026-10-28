#!/usr/bin/env python3
"""CPU-only local NudeNet 3.4.2 bridge. Emits one JSON object; any error exits nonzero."""
import argparse
import importlib.metadata
import json
import sys
import time

BLOCK_CLASSES = {
    "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED",
    "MALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED", "ANUS_EXPOSED",
}
# Intentionally conservative starting thresholds, NOT production-calibrated values.
THRESHOLD = 0.18

def scan(image, detector):
    import cv2
    if image is None or len(image.shape) < 2 or image.shape[0] < 4 or image.shape[1] < 4:
        raise ValueError("invalid image")
    h, w = image.shape[:2]
    regions = [("full", image)]
    # Independent image regions catch details lost when downscaling the entire 9:16 frame.
    if h > 256 and w > 256:
        regions += [("upper", image[:h // 2 + h // 8, :]),
                    ("lower", image[h // 2 - h // 8:, :]),
                    ("center", image[h // 4:3 * h // 4, w // 8:7 * w // 8])]
    hits = []
    for name, region in regions:
        detected = detector.detect(region)
        if not isinstance(detected, list):
            raise ValueError("invalid NudeNet result")
        for d in detected:
            if not isinstance(d, dict) or not isinstance(d.get("class"), str):
                raise ValueError("malformed detector output")
            if d["class"] in BLOCK_CLASSES and float(d.get("score", -1)) >= THRESHOLD:
                hits.append({"category": d["class"], "confidence": round(float(d["score"]), 5), "region": name})
    return hits

def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--image")
    mode.add_argument("--video")
    args = parser.parse_args()
    import cv2
    from nudenet import NudeDetector
    start = time.monotonic()
    detector = NudeDetector()
    version = importlib.metadata.version("nudenet")
    if version != "3.4.2":
        raise RuntimeError("unvalidated NudeNet version: " + version)
    path = args.image or args.video
    unsafe = []
    frames = 0
    expected = 0
    if args.image:
        image = cv2.imread(path, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("unreadable image")
        unsafe = scan(image, detector)[:20]
        frames = expected = 1
    else:
        cap = cv2.VideoCapture(path)
        if not cap.isOpened():
            raise ValueError("unreadable video")
        expected = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                if frame is None:
                    raise ValueError("decoded null frame")
                hits = scan(frame, detector)
                if hits and len(unsafe) < 30:
                    unsafe.append({"frame": frames, "detections": hits[:8]})
                frames += 1
        finally:
            cap.release()
        if expected < 1 or frames != expected:
            raise ValueError("incomplete video decode: decoded=" + str(frames) + " expected=" + str(expected))
    result = {
        "status": "UNSAFE" if unsafe else "SAFE",
        "reason": "explicit-anatomy region detected" if unsafe else "no exposed-anatomy regions above configured threshold",
        "detector": "NudeNet",
        "version": version,
        "threshold": THRESHOLD,
        "framesInspected": frames,
        "framesDecoded": frames,
        "framesExpected": expected,
        "coverage": "every decoded frame; full and three overlapping crops",
        "detections": unsafe,
        "elapsedSeconds": round(time.monotonic()-start, 3),
    }
    print(json.dumps(result, separators=(",", ":")))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("CONTENT_SAFETY_SCANNER_UNAVAILABLE: "+str(error), file=sys.stderr)
        sys.exit(2)
