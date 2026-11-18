# Unified AutoNewsRoller release validation and rollback

**Status:** DRAFT / NOT APPROVED FOR PRODUCTION  
**Integration branch:** `integration/autonewsroller-unified-20261117`  
**PR:** [#61](https://github.com/tndmadman/AutoNewsRoller/pull/61) targeting `main`  
**Recovery ancestor:** `4128294c6b6fcb04a6a99a848265da6bd4c0f4c0`

## Purpose and safety rules

This document guides a release operator through the exact-head checks required before approving a unified branch. Never infer safety or compatibility from a previous pull request or a passed unit test on a different SHA.

The controller and all workers must use the same verified commit. A worker success message does **not** replace policy-version, hash-bound MP4 safety approval. Do not bypass NudeNet 3.4.2, OpenNSFW2 0.19.0, any decoded frame, image inspection, post-composition checks or AIGC disclosure. TikTok's creator-side AI content label must be set separately.

## Prepare an isolated Windows acceptance checkout

1. Record the PR head commit SHA and confirm it is the exact commit under review.
2. Stop new test-queue claims in the **test environment**. Do not stop or replace the production controller as part of the acceptance trial.
3. Use a separate repository checkout and **separate** configuration, controller state, output archive, ComfyUI workspace and worker identity. Do not point the test controller at production state.
4. Back up production `data/command_center_state.json`, `defaults.txt`, private worker settings, and `output/` without deleting anything. Record the last verified production SHA for rollback.
5. Run `build_windows.bat` and `batch_create_news_videos_windows.bat --self-test` from the isolated checkout.
6. Run both dedicated Python safety detector health checks and `tools/verify_content_safety_windows.ps1`. Confirm model versions and configured safe-image policy.
7. Confirm ComfyUI checkpoint, Ollama, Kokoro, Qwen fallback, NVIDIA driver, FFmpeg/FFprobe, and both independent safety models are ready. Capture actual versions and GPU state. A missing service is a failed preflight, not permission to create a placeholder video.

## Generate and inspect representative canaries

Using the **test** Command Center and worker on the exact SHA, manually enqueue one grounded eligible story in each production mode:

- `STANDARD`: about 70 seconds; requires accepted ComfyUI editorial illustrations.
- `MEME_RAGEBAIT`: independently eligible, short-form satire; requires accepted illustrated scenes and factual grounding.
- `FRAMING_EXPOSURE`: manually requested source-framing review, not automatic pairing; uses grounded procedural evidence graphics rather than invented incident photos.

For each mode, retain the local worker log, generated video, JSON sidecar, failed/accepted image attempt records, saved checkpoint, source evidence, narration engine, image checkpoint, actual encoder and elapsed stage timing. **Do not upload any acceptance canary to a social platform.**

After all three are generated, audit the isolated batch and write representative frames:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/unified_acceptance_windows.ps1 -BatchRoot "C:\isolated\AutoNewsRoller\output\acceptance_batch" -ExpectedFps 30 -CaptureFrames
```

This script creates `unified_acceptance_report.json` and an optional `representative_frames/` directory inside the selected batch. It verifies existing output files but **does not generate them, launch a controller, rerun the image detectors or prove the files were produced on the desired GPU**. Inspect the JSON report and every failed static check. Independently verify detector health and final decoded-video safety.

At phone size, inspect actual motion and transitions, the AIGC disclosure on every frame, caption readability, editorial framing and source attribution, image quality, narration audibility, music/intro level and timing. Match captions and visuals to sourced claims. An automated fiducial/region check does not prove real phone legibility.

## Fault-injection release gate

In the isolated checkout, inject worker crash after narration, crash after rendering, stale lease, two workers claiming the same job, upload acknowledgement lost after successful transfer, cancellation, deleted/scrapped output, corrupt or changed checkpoint and changed safety policy. Reproduce errors in the controller and worker logs. Verify correct epoch reuse, stable evidence hashes, no duplicate publish, no canceled work resurrection and no unsafe cached final output approval.

Repeat required ComfyUI fault cases: absent checkpoint, current-prompt failure, GPU OOM with bounded recovery, rejected original image, rejected post-composition image, unavailable detector and incomplete accepted scene set. Confirm every unsafe/missing case blocks rendering or follows a controlled bounded retry; do not clear the global ComfyUI queue.

## CI and review

Require **all** GitHub Actions jobs on the exact final SHA: Windows self-test, Linux headless, Windows Python bootstrap, Linux and Windows historical archive regressions, dual-model synthetic MP4 checks, native FFmpeg smoke, and Chromium/Firefox browser regression. If a prior run was canceled by a newer commit, it is not evidence of a pass for the newer SHA. Rerun transient environment failures rather than disabling tests.

The release record must include base/final SHA, exact CI job outcomes and links, actual Windows GPU logs, three canary report JSON files, independent detector calibration results, and signed human approval. A draft PR cannot become ready based solely on a planned test.

## Deployment and rollback

After **all release gates pass**, schedule a maintenance window and drain production work without deleting jobs. Preserve unprocessed queues, historical MP4s, controller state and configuration. Back up archives and checkpoint directories. Deploy the approved controller and worker **together** at the exact recorded SHA. Verify readiness and observe new jobs before allowing publication.

On any regression, stop new claims and drain/stop updated processes, restore the last approved controller and worker binaries and config together, restore backed-up controller state if schema compatibility requires it, and preserve new failed workspaces for investigation. Do not import a test MP4 into the production publishable namespace without its matching version-bound approval manifest. Never re-upload a previously unapproved or older-policy file.

## Still blocked

The linked GitHub connector cannot access the user's Windows workstation, actual model runtime, private production state, archived social videos or licensed detector calibration corpus. Such tests must be completed in the isolated workstation environment; no pass should be claimed without real evidence. The end-to-end crash/lease/upload fault injections and resource-efficiency measurements also require real test execution.

**Current release decision:** NOT READY. `main` must remain unchanged.
