#!/usr/bin/env python3
"""Pinned NudeNet 3.4.2 CPU scanner; CLI and optional resident NDJSON host."""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import sys
import time

BLOCK_CLASSES = {
    "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED",
    "MALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED", "ANUS_EXPOSED",
}
# NudeNet 3.4.2 also reports ordinary anatomy and covered regions.
# These are recognized classes, not explicit-content block classifications.
KNOWN_CLASSES = BLOCK_CLASSES | {
    "ARMPITS_EXPOSED", "BELLY_EXPOSED", "FEET_EXPOSED", "MALE_BREAST_EXPOSED",
    "FACE_FEMALE", "FACE_MALE", "FEMALE_GENITALIA_COVERED",
    "BUTTOCKS_COVERED", "FEMALE_BREAST_COVERED", "ANUS_COVERED",
    "ARMPITS_COVERED", "BELLY_COVERED", "FEET_COVERED",
}
# NudeNet 3.4.2 internally hardcodes NMS score threshold 0.25; this is NOT production calibrated.
THRESHOLD = 0.25

def cpu_threads():
    raw = os.environ.get("AUTONEWS_SAFETY_CPU_THREADS", "8")
    try:
        threads = int(raw)
    except ValueError as error:
        raise ValueError("invalid safety CPU thread budget") from error
    if not 1 <= threads <= 32:
        raise ValueError("safety CPU thread budget outside 1..32")
    return threads

def cpu_detector():
    import nudenet
    import onnxruntime as ort
    from nudenet import NudeDetector
    detector = NudeDetector()
    # NudeNet's built-in session uses ONNX Runtime's unrestricted CPU pool.
    # Rebind the SAME pinned model, execution provider and postprocessing,
    # with a bounded inference pool. Never skip frames or image regions.
    path = Path(nudenet.__file__).resolve().parent / "320n.onnx"
    if not path.is_file():
        raise RuntimeError("pinned NudeNet 320n.onnx model missing")
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = cpu_threads()
    opts.inter_op_num_threads = 1
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    detector.onnx_session = ort.InferenceSession(
        str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    if detector.onnx_session.get_providers() != ["CPUExecutionProvider"]:
        raise RuntimeError("bounded NudeNet CPU session unavailable")
    return detector

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
            label = d["class"]
            confidence = float(d.get("score", -1))
            if not (0 <= confidence <= 1):
                raise ValueError("invalid detector confidence")
            # Validate the pinned model vocabulary independently of the explicit-content policy.
            if label not in KNOWN_CLASSES:
                raise ValueError("unrecognized detector class: " + label)
            if label in BLOCK_CLASSES and confidence >= THRESHOLD:
                hits.append({"category": label, "confidence": round(confidence, 5), "region": name})
    return hits

def disclosure_present(frame):
    """Check the actual disclosure banner, two distinct text lines and its fiducial.

    The fiducial is an integrity aid, NOT sufficient evidence by itself.
    Coordinates use the final overlay's 1080x1920 reference layout.
    """
    import numpy as np
    h, w = frame.shape[:2]
    if h < 640 or w < 480:
        return False

    def rect(x1, y1, x2, y2):
        xa, xb = round(x1 * w / 1080), round(x2 * w / 1080)
        ya, yb = round(y1 * h / 1920), round(y2 * h / 1920)
        return frame[ya:yb, xa:xb, :3]

    marker = rect(356, 128, 371, 148)
    if marker.size == 0:
        return False
    b, g, r = marker[:, :, 0], marker[:, :, 1], marker[:, :, 2]
    magenta_fraction = np.mean((r > 125) & (b > 90) & (g < 145))
    if magenta_fraction < 0.65:
        return False

    # Ensure the backing panel remains dark rather than accepting a lone
    # colored pixel pasted anywhere in an otherwise unlabeled frame.
    panel = rect(376, 126, 386, 145)
    if panel.size == 0 or np.mean(np.max(panel, axis=2) < 100) < 0.70:
        return False

    for y1, y2 in ((145, 170), (184, 211)):
        ink = rect(390, y1, 1043, y2)
        if ink.size == 0:
            return False
        # Bright foreground ink must occupy both distinct lines, including
        # the lower "NOT ACTUAL FOOTAGE" text, after MP4 compression.
        if np.mean(np.min(ink, axis=2) > 170) < 0.006:
            return False
    return True

def run_scan(kind, path, detector, version, progress=None):
    import cv2
    started = time.monotonic()
    unsafe = []
    frames = expected = disclosure_frames = 0
    model_wall = decode_wall = 0.0
    if kind == "image":
        frame = cv2.imread(path, cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError("unreadable image")
        t = time.monotonic()
        unsafe = scan(frame, detector)[:20]
        model_wall += time.monotonic() - t
        frames = expected = 1
    else:
        cap = cv2.VideoCapture(path)
        if not cap.isOpened():
            raise ValueError("unreadable video")
        expected = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        try:
            while True:
                t = time.monotonic()
                ok, frame = cap.read()
                decode_wall += time.monotonic() - t
                if not ok:
                    break
                if frame is None:
                    raise ValueError("decoded null frame")
                if disclosure_present(frame):
                    disclosure_frames += 1
                t = time.monotonic()
                hits = scan(frame, detector)
                model_wall += time.monotonic() - t
                if hits and len(unsafe) < 30:
                    unsafe.append({"frame": frames, "detections": hits[:8]})
                frames += 1
                if progress is not None and frames % 30 == 0:
                    progress(frames, expected)
        finally:
            cap.release()
        if expected < 1 or frames != expected:
            raise ValueError("incomplete video decode: decoded=" + str(frames) + " expected=" + str(expected))
    if progress is not None:
        progress(frames, expected)
    missing_disclosure = kind == "video" and disclosure_frames != frames
    return {
        "status": "UNSAFE" if unsafe else ("REVIEW_REQUIRED" if missing_disclosure else "SAFE"),
        "reason": "explicit-anatomy region detected" if unsafe else
                  ("missing AI disclosure in one or more final frames" if missing_disclosure else
                   "no exposed-anatomy regions above configured threshold"),
        "detector": "NudeNet", "version": version, "threshold": THRESHOLD,
        "framesInspected": frames, "framesDecoded": frames, "framesExpected": expected,
        "disclosureVerified": not missing_disclosure,
        "disclosureFramesVerified": disclosure_frames,
        "coverage": "every decoded frame; full and three overlapping crops",
        "detections": unsafe,
        "decodeWallSeconds": round(decode_wall, 3),
        "modelAndPrepostWallSeconds": round(model_wall, 3),
        "elapsedSeconds": round(time.monotonic()-started, 3),
    }

def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--image")
    mode.add_argument("--video")
    mode.add_argument("--health", action="store_true")
    mode.add_argument("--serve", action="store_true")
    parser.add_argument("--backend", choices=("cpu","cuda"), default="cpu")
    parser.add_argument("--cuda-device", type=int, default=0)
    parser.add_argument("--parity-manifest")
    parser.add_argument("--vram-reserve-mb", type=int, default=4096)
    args = parser.parse_args()
    import cv2
    cv2.setNumThreads(1)
    loading = time.monotonic()
    metadata={"backend":"cpu","provider":"CPU","cpuThreads":cpu_threads()}
    if args.backend=="cuda":
        from safety_cuda_backend import nudenet_cuda
        detector,metadata=nudenet_cuda(args.cuda_device,args.parity_manifest,
                                       args.vram_reserve_mb)
    else:
        detector = cpu_detector()
    version = importlib.metadata.version("nudenet")
    if version != "3.4.2":
        raise RuntimeError("unvalidated NudeNet version: " + version)
    startup_seconds = time.monotonic()-loading
    if args.health:
        import numpy as np
        probe = np.zeros((192, 192, 3), dtype=np.uint8)
        if not isinstance(detector.detect(probe), list):
            raise ValueError("detector failed synthetic health test")
        print(json.dumps({"status":"READY", "detector":"NudeNet", "version":version,
                          "policyVersion":"content-safety-2026-10-28-v3",
                          "test":"detector loaded and responded to synthetic safe image"}))
    elif args.serve:
        from safety_scanner_ipc import serve
        serve("NudeNet", version, lambda kind, path, callback:
              run_scan(kind, path, detector, version, callback), startup_seconds, metadata)
    else:
        kind = "image" if args.image else "video"
        result=run_scan(kind, args.image or args.video, detector, version)
        result.update(metadata)
        print(json.dumps(result,separators=(",", ":")))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("CONTENT_SAFETY_SCANNER_UNAVAILABLE: " + str(error), file=sys.stderr)
        sys.exit(2)
