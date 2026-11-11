# Production failure recovery — repair branch 2026-11-11

> **Draft integration work.** Do not deploy to an active production controller or
> merge PR #58 into `main` based on this document. Content-safety calibration and
> real RTX 3090 worker tests are still release blockers.

## Purpose and current implementation

AutoNewsRoller formerly restarted the full news pipeline after many failures.
The production worker also created a new timestamped workspace on every claim.
Previously accepted scripts, narration, illustrations, and rendered videos could
not be resumed by the normal retry path.

This repair branch implements an initial recoverable production contract:

1. **Early eligibility checks.** `ProductionPreflight` checks basic story and
   mode eligibility, sensitive-story illustration policy, settings, meme editorial
   restrictions, ComfyUI endpoint and checkpoint existence before AI generation.
   Checks run on the worker, not just the controller. The refreshed fact package is
   checked again before script generation. The visual and final safety gates remain.
2. **Structured retry decisions.** `FailurePolicy` assigns a stable failure code
   and whether automatic whole-job recovery is allowed. The worker reports a code;
   the controller enforces its own retry decision. Permanent policy restrictions,
   missing required Comfy scenes, exhausted script validation, invalid settings,
   and unavailable safety scanners do not get automatic full-job retries. Retry
   delays are bounded exponential steps; existing total attempt budgets persist.
3. **Prompt-specific ComfyUI errors.** `ComfyImageGenerator` checks the history
   status and `execution_error` / `execution_interrupted` messages for its own
   submitted prompt. A reported execution failure ends the wait promptly; a
   genuine missing result remains bounded by the existing timeout.
4. **Durable artifacts.** A controller production request receives a stable
   `productionEpoch`. Automatic retry claims use the same
   `output/remote_worker/<job>_<epoch>/` workspace, guarded by an exclusive file
   lock. A new manual request gets a new epoch. The checkpoint manifest is
   `slot_001/production_checkpoint.json` for worker jobs (one manifest per
   production slot), written atomically and rejected when corrupt.
5. **Stage-level reuse.** Script, narration WAV and timing metadata, visual
   prompt plan, individual accepted ComfyUI scene artifacts, and the final
   approved video can be reused. Every recorded file is compared against the
   current SHA-256 hash; each stage also requires matching production input and
   policy identifiers. Accepted scenes are rechecked with deterministic quality
   inspection, both mandatory still-image safety services, and the configured
   optional vision review before reuse. Final video reuse additionally verifies
   the saved export-approval manifest. No unsafe or review-required output is
   accepted as a substitute for a missing asset.
6. **Operator visibility.** The Command Center shows the failure code, last
   error, recommended corrective action, last failure, and next retry time.
   The read-only `tools/analyze_production_failures.py` summarizes controller
   failures and optionally event logs, worker logs, and checkpoint files.

These optimizations do not change the NudeNet 3.4.2 or OpenNSFW2 0.19.0
requirements, their thresholds, final full-frame MP4 scans, or the export gates.
They do not re-enable automatic Narrative Review pairing; this remains manual
by default.

## Failure categories and retry model

The `FailurePolicy.Code` model includes:
`POLICY_BLOCKED`, `SOURCE_INVALID`, `STALE_EVIDENCE`,
`CONFIGURATION_INVALID`, `SERVICE_UNAVAILABLE`, `OLLAMA_FAILURE`,
`SCRIPT_VALIDATION_FAILED`, `TTS_FAILURE`, `COMFY_EXECUTION_FAILED`,
`COMFY_TIMEOUT`, `IMAGE_QUALITY_REJECTED`, `IMAGE_SAFETY_REJECTED`,
`SAFETY_SCANNER_UNAVAILABLE`, `VISUAL_ASSETS_MISSING`,
`RENDER_FAILURE`, `FINAL_SAFETY_BLOCKED`, `UPLOAD_FAILURE`,
`WORKER_LEASE_EXPIRED`, and `UNKNOWN_FAILURE`.

Old workers without a structured code still use a conservative message/stage
classification fallback. A configured maximum retry count remains in force;
recoverable does **not** mean unlimited attempts. GPU OOM handling retains
existing exclusive scheduling and one local memory-recovery attempt. Workers
must not clear global ComfyUI queues to fix an individual prompt.

## Read-only diagnostics

Run from the repository root:

```powershell
python tools/analyze_production_failures.py --state data/command_center_state.json --json output/failures.json --csv output/failures.csv
```

Optional inputs can be repeated:

```powershell
python tools/analyze_production_failures.py --state data/command_center_state.json --events output/runtime/events.jsonl --log output/runtime/worker_001.log --checkpoint output/remote_worker/JOB_EPOCH/slot_001/production_checkpoint.json
```

This utility does not delete, requeue, or alter a job. Historical data may not
contain all failure codes, attempt times, GPU utilization, or energy readings.
Do not infer watt-hours or before/after savings from missing measurements.

## Windows test procedure

1. Stop new job claims and drain active work. Back up
   `data/command_center_state.json`, `defaults.txt`, and the whole
   `output/` archive. Do not delete completed videos or job workspaces.
2. Test the repair branch in a separate checkout. Keep the existing validated
   installation and controller/worker unchanged during this trial.
3. Run `build_windows.bat` and
   `batch_create_news_videos_windows.bat --self-test`. Run
   `python tools/test_production_failures.py` for report fixtures.
4. Run `tools/verify_content_safety_windows.ps1` with the dedicated safety
   environment and confirm **both** detectors report their required versions.
5. On the RTX 3090 machine, validate one job each in
   `STANDARD`, `MEME_RAGEBAIT`, and `FRAMING_EXPOSURE` with actual
   Ollama, Kokoro/Qwen3-TTS, ComfyUI, FFmpeg, and full video safety scanning.
6. Inject failures **only in this isolated test**: blocked sensitive story,
   missing checkpoint, rejected sixth image, worker interruption, upload
   failure after approval, and changed source/policy. Observe that accepted
   work is reused and no unapproved media is published.
7. Review the Command Center failure details and the read-only report against
   actual worker logs. Confirm history, manual Narrative Reviews, and videos
   from before the upgrade remain intact.

## Rollback

Drain the test worker. Restore the previous validated controller and worker
commit together. Restore backed-up configuration and controller state if schema
compatibility or queue behaviour is incorrect; do **not** copy test videos into
production without their exact approved sidecars and checks. Preserve saved
archives and separate failed test workspaces for diagnosis.

## Remaining work before production release

- Exact-head multi-platform CI must pass and the real RTX 3090 canaries above
  must be observed. Mock and fixture tests are not equivalent to GPU testing.
- Add full stage-level telemetry, accurate time/CPU/GPU baselines, and a
  before/after comparison from representative live job logs.
- Investigate persistent safety-scanner inference processes and content-addressed
  safety scan caching without skipping independent detector or MP4 frame checks.
- Extend checkpoints to all render sub-stages and strengthen publication and
  controller upload idempotency after interrupted acknowledgements.
- Add robust explicit service readiness classification for Ollama, both TTS
  engines, FFmpeg/FFprobe and both safety detectors, with bounded health caches.
- Add comprehensive fault-injection regression coverage for every pipeline mode,
  worker claim supersession, input invalidation, and ComfyUI prompt lifecycle.
- Bound and measure optional political/meme analysis resource use during heavy
  worker production; preserve manual-first Narrative Reviews.
- Complete the independent PR #58 safety-release blockers before considering
  a merge to main.
