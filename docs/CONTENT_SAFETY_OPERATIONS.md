# Content safety / AIGC transition (DRAFT — real worker validation pending)

## Mandatory release policy

Current approval policy: `content-safety-2026-10-28-v3`.
Older approvals expire. A worker cannot export a new MP4 unless both mandatory local detectors finish successfully, report the exact tested versions, and cover every final decoded frame:

- NudeNet 3.4.2: exposed-anatomy region detection; full frame and three overlapping crops.
- OpenNSFW2 0.19.0: independent pornographic-context scoring; all final decoded frames, plus upper and lower regions for still images.

NudeNet exposed-anatomy detection uses its effective 0.25 confidence floor. OpenNSFW2 hard-holds a frame at score >=0.70 and requires review at score >=0.20. These are **uncalibrated, conservative preliminary thresholds**, not measured error guarantees. OpenNSFW2 is a pornographic context classifier, **not** a comprehensive erotic-pose classifier. Misclassification and false negatives remain possible. Production release requires licensed evaluation data and acceptable accuracy/latency evidence.

The worker scans generated and composed images before use. When an image fails, the untouched procedural card is retained. The output video is then independently decoded and checked by both classifiers. A returned error, exception, unexpected response, incomplete frame coverage, unavailable model, missing disclosure, or policy mismatch blocks export.

Final MP4 approval references the exact SHA-256, policy version, full-frame audit, named detector versions and independent model results. The approved MP4 is copied to a `.pending` staging filename and promoted to a `.mp4` destination only after its provenance sidecar is written. The Command Center verifies its actual MP4 bytes and current manifest before offering upload or download options. Older worker versions lacking this evidence are not grandfathered in.

**Trust boundary:** The controller currently validates worker-supplied detector evidence and bytes, but does not independently rerun model inference. A compromised worker with the ability to forge sidecars remains a release threat. Restrict worker credentials and access, and do not describe manifest verification as cryptographic attestation.

## Install and verify

Use a dedicated compatible 64-bit Python 3.10–3.13 environment for the worker's `contentSafetyPython` configuration. The default is now `auto`, selecting `.venv-safety/Scripts/python.exe` on Windows or `.venv-safety/bin/python` on Linux. Legacy `contentSafetyPython=python` also resolves to this virtual environment; it does **not** use whichever system Python happens to be first on PATH. An override must be an explicit executable path. A missing environment blocks export with an actionable error.

**Windows first-time setup / repair (from the AutoNewsRoller project directory):**

Python 3.13 is supported by the pinned TensorFlow CPU 2.20 Windows wheel. The launcher searches for standalone 64-bit CPython 3.13, 3.12, 3.11, or 3.10. If none is installed, use `winget install --id Python.Python.3.13 -e --source winget` or install Python from python.org, open a new terminal, and rerun setup. Do not use ComfyUI's embedded Python for environment creation.

```powershell
.\setup_content_safety_windows.bat
```

The setup script creates `.venv-safety` with a detected standalone Python 3.10–3.13 if needed, installs all three pinned dependencies, loads both pretrained models and verifies the synthetic MP4 scanner smoke test. `setup_windows.bat` also calls this helper. **Drain/stop the worker before installing, then restart it** to load the correct interpreter. Python dependencies installed into ComfyUI, Qwen TTS, Kokoro or globally do not count.


```powershell
py -3.13 -m venv .venv-safety
.\.venv-safety\Scripts\python.exe -m pip install -r requirements-content-safety.txt
.\.venv-safety\Scripts\python.exe tools/content_safety_scan.py --health
.\.venv-safety\Scripts\python.exe tools/content_context_scan.py --health
.\.venv-safety\Scripts\python.exe tools/test_content_safety_scan.py
```

For Linux, use a compatible CPython version with the corresponding `.venv-safety/bin/python` commands.

TensorFlow CPU requires a large platform-specific wheel. OpenNSFW2 also needs its pretrained weights from the upstream release on its first run; supply a verified local copy in a controlled deployment if downloading is restricted. A failed dependency install or model download does **not** permit a lower-protection fallback.

The synthetic smoke tests prove decoder, dual-model startup, frame coverage and disclosure detection on blank scenes. They do **not** measure sexual-content precision/recall, real production throughput, or GPU pipeline compatibility.

## Windows workstation release-readiness evidence

With ComfyUI and Ollama running locally, use the native PowerShell verifier.
It will not upload or publish anything.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\verify_content_safety_windows.ps1 -Python .\.venv-safety\Scripts\python.exe -Video "output\path-to-an-actual-finished-video.mp4"
```

The script saves a JSON report and logs under
`output/content_safety/release_readiness/<timestamp>` and never reports
`PRODUCTION_READY` automatically. The real final video must be visually
reviewed for disclosure legibility, factual grounding and hidden unsafe details.
Pass `-FullArchive` only when intentionally scanning all local historical MP4
files, which can be lengthy. Without `-Video` the report explicitly records
that end-to-end video validation has not been performed.

## Visual and AIGC rules

STANDARD videos produce illustrated editorial imagery rather than presenting generated imagery as authenticated wire-service photographs. MEME_RAGEBAIT is labeled as satire. FRAMING_EXPOSURE uses source/fact graphics, not fabricated photographs. Sensitive incidents use procedural fact graphics rather than realistic synthetic incident reenactments.

Final rendered videos contain top-of-frame labels identifying AI visuals, narration and illustrations. The full-frame NudeNet video scanner verifies presence of the marker, dark backing, and distinct bright text regions on **every decoded frame**. It is not a formal OCR or phone-legibility certification. Verify social-platform safe regions and the finished phone-size presentation before release.

MP4 disclosures and sidecars do not switch on TikTok's creator-side AIGC setting. Operators must explicitly enable the platform's applicable AIGC label and verify source attribution before posting. Verified RSS sources do not guarantee a generated scene is truthful or that an upload meets a platform's policies.

## Historical media

From Windows PowerShell, in the project directory:

```powershell
.\.venv-safety\Scripts\python.exe tools\audit_historical_outputs.py --root .
```

For videos stored elsewhere, add `--extra-dir "D:\Your\Other\Videos"` (repeat for multiple directories). The audit inventories all MP4s recursively in `output` and in any extra directories, not just the current `final_videos` folder. It runs both mandatory detectors on every file and verifies every decoded frame. **Default is read-only to video files and rescans everything**; `--resume` optionally reuses verified same-hash, same-policy, same-model full scans.

Reports are written to `output/content_safety/archive-audit-latest.json` and timestamped `archive-audit-*.json` and `.csv`, including totals and statuses `SAFE`, `UNSAFE`, `REVIEW_REQUIRED`, `SCANNER_UNAVAILABLE`. The terminal and report explicitly say `NO_VIDEO_FILES` if no MP4s are accessible (exit 3), or `INCOMPLETE` if even one mandatory scanner failed (exit 2). No videos are deleted, and the default never moves them. **A technical SAFE scan is not a repost authorization**, and records always say `mustNotRepost=true` until independently cleared.

Pass `--quarantine-flagged` (alias: `--quarantine-confirmed-unsafe`) only when explicitly choosing to move scanner-confirmed `UNSAFE` media into a local restricted quarantine directory. It never moves ambiguous `REVIEW_REQUIRED` or `SCANNER_UNAVAILABLE` videos. It also forces a fresh scan before quarantining previously cached unsafe findings. Do not delete uncertain evidence.

The archive audit **must be run on the workstation holding your historical videos**, or on a mounted directory containing them; it cannot scan TikTok's servers or discover videos that were never downloaded. Run `python tools/test_historical_audit.py` for mocked file-handling regression checks; those tests do not establish the safety of any real MP4.

The October 28 TikTok ban and any link between nudity removal and mass AIGC enforcement remain unverified. Do not imply that this code reverses strikes or proves the cause of account enforcement.

## Required release gates still outstanding

1. Complete licensed evaluation for explicit and ambiguous examples, challenging illustrations and nonsexual skin; measure per-category false positives and false negatives.
2. Verify OpenNSFW2/TensorFlow, NudeNet, ComfyUI, Ollama, Kokoro/Qwen and FFmpeg together on the actual Windows Threadripper/RTX 3090 worker.
3. Benchmark all-frame dual-detector scanning and reduce expensive startup costs without losing coverage. Each image currently launches detector subprocesses; each final video is independently decoded twice.
4. Check visual disclosure legibility and all standard, meme and framing rendered videos at phone size.
5. Validate historical archive files from the real workstation and restrict unsafe previews in all operational views.
6. Assess authenticated controller-side attestations or independent rescan to mitigate forged worker manifests.
7. Run full Java, browser, Python model-health, video smoke and Windows/Linux CI against the last commit before any merge.

No single detector, classifier threshold or safety workflow can make generated nudity literally impossible. The policy is intended to prevent identified prohibited content from being published and to block export when required checks cannot be completed.
