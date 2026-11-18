#!/usr/bin/env python3
"""Verify scanner CPU thread controls without loading model weights."""
import os
import sys
import tempfile
from pathlib import Path
from types import ModuleType

import content_safety_scan as scan

def main():
    old = os.environ.get("AUTONEWS_SAFETY_CPU_THREADS")
    try:
        os.environ.pop("AUTONEWS_SAFETY_CPU_THREADS", None)
        assert scan.cpu_threads() == 8
        os.environ["AUTONEWS_SAFETY_CPU_THREADS"] = "6"
        assert scan.cpu_threads() == 6
        for invalid in ("0", "33", "broken", "-1"):
            os.environ["AUTONEWS_SAFETY_CPU_THREADS"] = invalid
            try:
                scan.cpu_threads()
            except ValueError:
                pass
            else:
                raise AssertionError("accepted invalid CPU budget")
        os.environ["AUTONEWS_SAFETY_CPU_THREADS"] = "8"
        with tempfile.TemporaryDirectory() as tmp:
            pkg = Path(tmp, "nudenet")
            pkg.mkdir()
            (pkg / "320n.onnx").write_bytes(b"fixture only")
            package = ModuleType("nudenet")
            package.__file__ = str(pkg / "__init__.py")
            class Detector:
                def __init__(self):
                    self.onnx_session = None
            package.NudeDetector = Detector
            ort = ModuleType("onnxruntime")
            class Options:
                def __init__(self):
                    self.intra_op_num_threads = -1
                    self.inter_op_num_threads = -1
                    self.execution_mode = None
            class Session:
                def __init__(self, path, sess_options, providers):
                    assert Path(path) == pkg / "320n.onnx"
                    assert sess_options.intra_op_num_threads == 8
                    assert sess_options.inter_op_num_threads == 1
                    assert providers == ["CPUExecutionProvider"]
                def get_providers(self):
                    return ["CPUExecutionProvider"]
            ort.SessionOptions = Options
            ort.ExecutionMode = ModuleType("ExecutionMode")
            ort.ExecutionMode.ORT_SEQUENTIAL = "sequential"
            ort.InferenceSession = Session
            previous = {name: sys.modules.get(name) for name in ("nudenet","onnxruntime")}
            try:
                sys.modules["nudenet"] = package
                sys.modules["onnxruntime"] = ort
                assert isinstance(scan.cpu_detector().onnx_session, Session)
            finally:
                for name, module in previous.items():
                    if module is None:
                        sys.modules.pop(name, None)
                    else:
                        sys.modules[name] = module
    finally:
        if old is None:
            os.environ.pop("AUTONEWS_SAFETY_CPU_THREADS", None)
        else:
            os.environ["AUTONEWS_SAFETY_CPU_THREADS"] = old
    print("SafetyCpuThreadConfigTest PASS")
if __name__ == "__main__":
    main()
