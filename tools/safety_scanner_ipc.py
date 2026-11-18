#!/usr/bin/env python3
"""Bounded local NDJSON scanner protocol. No network listener; no media in stdout.

The sender supplies an exact SHA-256, and the scanner checks it both before
and after decoding. A transport failure cannot be turned into SAFE.
"""
import hashlib
import json
import os
import re
import sys
import time

HASH = re.compile(r"^[0-9a-f]{64}$")
MAX_REQUEST = 16384
MAX_RESPONSE = 262144

def file_hash(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(131072), b""):
            h.update(chunk)
    return h.hexdigest()

def emit(message):
    encoded = json.dumps(message, separators=(",", ":"), allow_nan=False)
    if len(encoded.encode("utf-8")) > MAX_RESPONSE:
        raise ValueError("scanner response exceeds protocol limit")
    print(encoded, flush=True)

def serve(detector, version, runner, startup_seconds, metadata=None):
    metadata = dict(metadata or {"backend": "cpu", "provider": "CPU"})
    if metadata.get("backend") not in ("cpu", "cuda"):
        raise ValueError("unrecognized scanner backend")
    emit({"event": "ready", "status": "READY", "detector": detector,
          "version": version, "policyVersion": "content-safety-2026-10-28-v3",
          "modelLoadSeconds": round(startup_seconds, 3),
          "pid": os.getpid(), **metadata})
    for line in sys.stdin.buffer:
        request_id = ""
        try:
            if len(line) > MAX_REQUEST:
                raise ValueError("oversized request")
            req = json.loads(line.decode("utf-8"))
            if not isinstance(req, dict):
                raise ValueError("invalid request")
            request_id = str(req.get("id", ""))
            if not (1 <= len(request_id) <= 96 and
                    all(c.isascii() and (c.isalnum() or c in "_-") for c in request_id)):
                raise ValueError("invalid request id")
            operation = req.get("operation")
            if operation == "shutdown":
                emit({"event": "result", "id": request_id, "status": "STOPPED"})
                break
            if operation not in ("image", "video"):
                raise ValueError("unknown operation")
            path = req.get("path")
            expected_sha = req.get("sha256")
            if not isinstance(path, str) or not os.path.isabs(path) or not os.path.isfile(path):
                raise ValueError("unavailable input file")
            if not isinstance(expected_sha, str) or not HASH.fullmatch(expected_sha):
                raise ValueError("missing validated input hash")
            if file_hash(path) != expected_sha:
                raise ValueError("input hash mismatch before scan")
            started = time.monotonic()
            def progress(done, expected):
                emit({"event": "progress", "id": request_id, "detector": detector,
                      "backend": metadata["backend"], "framesProcessed": int(done),
                      "framesExpected": int(expected),
                      "elapsedSeconds": round(time.monotonic()-started, 3)})
            result = runner(operation, path, progress)
            if file_hash(path) != expected_sha:
                raise ValueError("input changed during scan")
            if result.get("status") not in ("SAFE", "UNSAFE", "REVIEW_REQUIRED"):
                raise ValueError("invalid scanner result status")
            result.update(metadata)
            result["scannerPid"] = os.getpid()
            result["modelLoadSeconds"] = round(startup_seconds, 3)
            emit({"event": "result", "id": request_id, "result": result})
        except Exception as error:
            # Never return SAFE on malformed requests, crashes or decode failures.
            emit({"event": "result", "id": request_id,
                  "result": {"status": "SCANNER_UNAVAILABLE",
                             "reason": str(error)[:240],
                             "detector": detector, "version": version}})
