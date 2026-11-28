#!/usr/bin/env python3
"""Never launch a persistent Qwen helper from a canary acceptance run."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).with_name("qwen3_tts.py")
spec = importlib.util.spec_from_file_location("canary_qwen_helper", MODULE)
qwen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qwen)

class CanaryHelperGuardTest(unittest.TestCase):
    def test_no_helper_start_during_canary(self):
        with patch.dict(os.environ, {"AUTONEWS_CANARY_NO_HELPER_START": "1"}), \
             patch.object(qwen, "health", return_value=None), \
             patch.object(qwen.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(RuntimeError, "auto-start is disabled"):
                qwen.start_server(Path("/tmp/test-does-not-exist"), "http://127.0.0.1:1")
            spawn.assert_not_called()

if __name__ == "__main__":
    unittest.main()
