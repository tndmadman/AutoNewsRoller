#!/usr/bin/env python3
"""Offline regression fixtures for the read-only production failure reporter."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE = Path(__file__).with_name("analyze_production_failures.py")
spec = importlib.util.spec_from_file_location("production_failures", MODULE)
reporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reporter)


class ProductionFailureReportTests(unittest.TestCase):
    def test_structured_and_legacy_failures(self):
        state = {
            "stories": {
                "job-1": {
                    "status": "COMPLETE", "claimCount": 2,
                    "productionAttempts": [
                        {"event": "FAILED", "stage": "VISUALS",
                         "failureType": "COMFY_EXECUTION_FAILED", "error": "CUDA"},
                        {"event": "CLAIMED", "stage": "VERIFY"}
                    ]
                },
                "job-2": {
                    "status": "FAILED", "claimCount": 4,
                    "productionAttempts": [
                        {"event": "FAILED", "stage": "SCRIPT",
                         "failureType": "SCRIPT_VALIDATION_FAILED"},
                        {"event": "FAILED", "stage": "SCRIPT",
                         "failureType": "SCRIPT_VALIDATION_FAILED"}
                    ]
                },
                "job-3": {
                    "status": "QUEUED", "lastFailure": "legacy failure",
                    "failureType": "VISUAL_FAILURE",
                    "lastFailedAt": "2026-11-11T09:00:00Z"
                }
            }
        }
        report = reporter.summarize(state)
        self.assertEqual(1, report["completed"])
        self.assertEqual(1, report["failed"])
        self.assertEqual(.5, report["terminalSuccessRate"])
        self.assertEqual(2, report["failureCategories"]["SCRIPT_VALIDATION_FAILED"])
        self.assertEqual(1, report["failureCategories"]["VISUAL_FAILURE"])
        self.assertEqual("job-2", report["repeatedFailures"][0]["storyId"])

    def test_events_logs_and_checkpoints(self):
        report = reporter.summarize(
            {"stories": {}},
            events=[{"type": "progress", "payload": {"stage": "VISUALS"}}],
            logs=["COMFYUI FAILED: CUDA", "COMFY_TIMEOUT", "unrelated message"],
            checkpoints=[{"entries": {"SCRIPT": {}, "NARRATION": {}}}]
        )
        self.assertEqual(1, report["eventCounts"]["progress:VISUALS"])
        self.assertEqual(1, report["logMarkers"]["COMFYUI FAILED"])
        self.assertEqual(2, report["checkpoints"]["recordedStageEntries"])
        self.assertIsNone(report["terminalSuccessRate"])

    def test_invalid_json_is_skipped_without_modifying_source(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.jsonl"
            original = '{"type":"progress"}\ninvalid json\n'
            path.write_text(original, encoding="utf-8")
            self.assertEqual(1, len(list(reporter.records(path))))
            self.assertEqual(original, path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
