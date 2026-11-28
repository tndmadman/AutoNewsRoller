#!/usr/bin/env python3
"""Black-box detector smoke: actual encoded video, every frame, two-line disclosure.

Uses only synthetic blank frames; NOT a substitute for licensed nudity fixtures.
"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def run(scanner, video):
    process = subprocess.run(
        [sys.executable, str(scanner), "--video", str(video)],
        capture_output=True, text=True, timeout=240, check=False)
    if process.returncode:
        raise AssertionError("scanner failed: " + process.stderr[-800:])
    return json.loads(process.stdout)


def make_video(path, include_second_line):
    import cv2
    import numpy as np
    w, h = 540, 960
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 3, (w, h))
    if not writer.isOpened():
        raise AssertionError("OpenCV MP4 writer unavailable")
    try:
        for i in range(3):
            frame = np.full((h, w, 3), (27, 24, 21), dtype=np.uint8)
            cv2.rectangle(frame, (175, 58), (526, 114), (17, 9, 5), -1)
            cv2.rectangle(frame, (178, 64), (186, 74), (230, 25, 255), -1)
            cv2.putText(frame, "AI-GENERATED VISUALS / AI NARRATION",
                        (195, 83), cv2.FONT_HERSHEY_SIMPLEX, 0.40, (255, 255, 255), 1)
            if include_second_line:
                cv2.putText(frame, "ILLUSTRATION - NOT ACTUAL FOOTAGE",
                            (195, 103), cv2.FONT_HERSHEY_SIMPLEX, 0.40, (255, 255, 255), 1)
            writer.write(frame)
    finally:
        writer.release()
    if not path.is_file() or path.stat().st_size < 200:
        raise AssertionError("Failed to create synthetic MP4")


def main():
    scanner = Path(__file__).with_name("content_safety_scan.py")
    with tempfile.TemporaryDirectory(prefix="anr-content-safety-") as temp:
        root = Path(temp)
        good = root / "disclosed.mp4"
        bad = root / "missing-lower-disclosure.mp4"
        make_video(good, True)
        make_video(bad, False)
        safe = run(scanner, good)
        blocked = run(scanner, bad)
        assert safe["status"] == "SAFE", safe
        assert safe["framesDecoded"] == safe["framesInspected"] == safe["framesExpected"] == 3, safe
        assert safe["disclosureFramesVerified"] == 3, safe
        assert blocked["status"] == "REVIEW_REQUIRED", blocked
        assert blocked["disclosureFramesVerified"] == 0, blocked
        context = run(Path(__file__).with_name("content_context_scan.py"), good)
        assert context["status"] == "SAFE", context
        assert context["detector"] == "OpenNSFW2", context
        assert context["framesInspected"] == context["framesExpected"] == 3, context
        print("ContentSafetyScannerSmoke PASS (two independent models; every MP4 frame; both disclosure lines mandatory)")
        print("Licensed nudity classifier precision/recall: NOT TESTED")


if __name__ == "__main__":
    main()
