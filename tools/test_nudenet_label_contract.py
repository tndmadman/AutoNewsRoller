#!/usr/bin/env python3
"""Synthetic label-contract regression; no model download or real imagery needed."""
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import content_safety_scan as scanner


class Fixture:
    shape = (192, 192, 3)


def detection(label, score=0.9):
    return {"class": label, "score": score}


class LabelContractTests(unittest.TestCase):
    def scan(self, *detections):
        with patch.dict(sys.modules, {"cv2": SimpleNamespace()}):
            return scanner.scan(Fixture(), SimpleNamespace(detect=lambda _: list(detections)))

    def test_all_18_known_classes(self):
        self.assertEqual(len(scanner.KNOWN_CLASSES), 18)
        for label in scanner.KNOWN_CLASSES:
            with self.subTest(label=label):
                hits = self.scan(detection(label))
                self.assertEqual(bool(hits), label in scanner.BLOCK_CLASSES)

    def test_armpit_does_not_abort_explicit_detection(self):
        hits = self.scan(detection("ARMPITS_EXPOSED"),
                         detection("FEMALE_GENITALIA_EXPOSED"))
        self.assertEqual([hit["category"] for hit in hits], ["FEMALE_GENITALIA_EXPOSED"])

    def test_explicit_threshold_unchanged(self):
        for label in scanner.BLOCK_CLASSES:
            self.assertFalse(self.scan(detection(label, 0.24)))
            self.assertTrue(self.scan(detection(label, 0.25)))

    def test_unknown_label_fails_closed(self):
        for label in ("FUTURE_EXPOSED", "UNKNOWN"):
            with self.assertRaises(ValueError):
                self.scan(detection(label))

    def test_malformed_confidence_fails_closed(self):
        for score in (-0.1, 1.1, float("nan")):
            with self.assertRaises(ValueError):
                self.scan(detection("ARMPITS_EXPOSED", score))


if __name__ == "__main__":
    unittest.main()
