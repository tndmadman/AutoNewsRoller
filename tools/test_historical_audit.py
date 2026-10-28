#!/usr/bin/env python3
"""File handling regression tests. Mock scanners; not a NudeNet accuracy benchmark."""
from pathlib import Path
import tempfile
from unittest.mock import patch

import audit_historical_outputs as audit


def sample_video(root, suffix):
    file = root / "output" / suffix
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(b"mock-mp4-%s" % suffix.as_posix().encode())
    return file


def fake_scan(_root, path, script):
    if "content_safety_scan" in script:
        model = "NudeNet"
        return {
            "detector": model, "version": "3.4.2", "status": "SAFE",
            "reason": "mock anatomy safe", "threshold": 0.25,
            "framesDecoded": 2, "framesExpected": 2, "framesInspected": 2,
            "disclosureFramesVerified": 2, "disclosureVerified": True,
            "coverage": "every decoded frame; full and three overlapping crops",
        }
    return {
        "detector": "OpenNSFW2", "version": "0.19.0", "status": "SAFE",
        "reason": "mock context safe", "reviewThreshold": 0.20,
        "hardBlockThreshold": 0.70, "framesDecoded": 2, "framesExpected": 2,
        "framesInspected": 2,
        "coverage": "every decoded frame; complete frame; upper/lower crops on still images",
    }


def main():
    with tempfile.TemporaryDirectory(prefix="anr-archive-regression-") as temp:
        root = Path(temp)
        quiet = lambda message: None
        empty = audit.audit(root, progress=quiet)
        assert empty["status"] == "NO_VIDEO_FILES" and empty["total"] == 0

        old_final = sample_video(root, Path("historical/final_videos/video-A.mp4"))
        video_b = sample_video(root, Path("command_center/videos/video-B.mp4"))
        video_c = sample_video(root, Path("other_versions/older-2024.MP4"))
        video_d = root / "outside_old_video.mp4"
        video_d.write_bytes(b"mock-old-video")

        with patch.object(audit, "call_scanner", side_effect=fake_scan) as scan:
            first = audit.audit(root, extra_dirs=[video_d.parent], progress=quiet)
            assert first["total"] == 4
            assert first["counts"]["SAFE"] == 4
            assert scan.call_count == 8
            assert first["fullyScanned"] == 4
            assert all(entry["mustNotRepost"] for entry in first["files"])
            assert Path(first["reportPath"]).is_file()
            assert Path(first["csvPath"]).is_file()
            assert video_b.exists() and video_c.exists()

        with patch.object(audit, "call_scanner", side_effect=RuntimeError("should have been cached")) as scan:
            second = audit.audit(root, extra_dirs=[video_d.parent], resume=True, progress=quiet)
            assert second["status"] == "AUDIT_COMPLETE_TECHNICAL_ONLY"
            assert second["fromCache"] == 4 and scan.call_count == 0

        # Changing bytes must invalidate the prior approval. Both scanners now
        # error, and the file must NOT be reported as SAFE.
        old_final.write_bytes(b"changed mock-mp4 bytes")
        with patch.object(audit, "call_scanner", side_effect=RuntimeError("models missing")):
            third = audit.audit(root, extra_dirs=[video_d.parent], resume=True, progress=quiet)
            assert third["status"] == "INCOMPLETE"
            entry = next(e for e in third["files"] if "video-A" in e["file"])
            assert entry["status"] == "SCANNER_UNAVAILABLE"
            assert entry["mustNotRepost"]

        # Opt-in quarantine is allowed only on a new confirmed detector result.
        def unsafe(_root, path, script):
            reply = fake_scan(_root, path, script)
            if path == old_final and reply["detector"] == "NudeNet":
                reply["status"] = "UNSAFE"
                reply["reason"] = "mock exposure detection"
            return reply
        with patch.object(audit, "call_scanner", side_effect=unsafe):
            fourth = audit.audit(root, extra_dirs=[video_d.parent],
                                 quarantine_flagged=True, progress=quiet)
            assert fourth["counts"]["UNSAFE"] == 1
            assert fourth["quarantined"] == 1
            assert not old_final.exists()
            quarantine_item = next(e for e in fourth["files"] if "video-A" in e["file"])
            assert Path(root / quarantine_item["quarantinedAt"]).exists()

        # Scanned quarantine items should never be reimported into a new inventory.
        found = audit.inventory(root, [video_d.parent])
        assert old_final not in found and len(found) == 3
        print("HistoricalAuditSelfTest PASS (inventory, empty, cache, changed hash, quarantine, no repost)")


if __name__ == "__main__":
    main()
