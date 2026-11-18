# Unified reliability hardening implementation audit

**Repository:** `tndmadman/AutoNewsRoller`  
**Branch:** `integration/autonewsroller-unified-20261117`  
**Pull request:** [#61](https://github.com/tndmadman/AutoNewsRoller/pull/61), draft, base `main`  
**Ancestor reviewed:** `d95a2d601a56a7f9318462614b398771a9452edb`  
**Deployment approval:** NOT GRANTED

## Confirmed defects before modification

- Server video uploads allocated unique permanent names before claim
  authorization and did not enforce durable media identity. A lost HTTP
  acknowledgement could create duplicates.
- The controller's network callback ownership logic tolerated absent claim
  tokens and did not consistently fence lease expiry.
- `complete()` would commit another video version on repeated approved
  completion, losing current manual upload state and potentially starting
  another optional paired review.
- The first CI results at ancestor `d95a2d6` failed Java tests on an obsolete
  literal source-code assertion and failed Chromium and Firefox on an active
  retry displayed as a terminal failure panel.

## Implemented repairs

- **`CommandCenterStore.java`**: active lease/claim fencing; persisted upload
  SHA, epoch, claim and canonical media path; committed output identity;
  idempotent version commits; posted-state preservation; restart-aware replay.
- **`CommandCenterServer.java`**: preauthorized staging upload with a 2 GiB
  bound, content hash validation, deterministic namespace promotion and
  replay acknowledgements; secure progress and fail callbacks; completion
  sidecars from server-stored data.
- **`RemoteWorker.java`**: new upload SHA and epoch headers, epoch in completion,
  stop spending work after explicit controller stale-lease rejection,
  structured per-claim stage telemetry.
- **`NewsPipeline.java` and `ProductionServiceReadiness.java`**: lightweight
  Ollama model inventory check, narration bootstrap checks, FFmpeg/FFprobe
  capability checks, pinned-model safety health with bounded caching before
  expensive live work.
- **`FailurePolicy.java`**: missing Ollama model is a terminal configuration
  error, unavailable model service remains transient, mandatory scanner
  missing is terminal/fail-closed.
- **`ProductionStageTelemetry.java`**: append-only JSONL per claim, stage
  timestamps, attempt counts, Comfy generated/rejected attempts, checkpoint
  reuse and final acknowledgement. No prompts, article text or credentials.
- **`tools/analyze_production_failures.py`**: optional telemetry input,
  recorded stage mean, median, p95 and total milliseconds, image rejection
  ratio, retry claim counts and checkpoint reuse. Missing times are not
  estimated.
- **`tools/run_unified_canaries_windows.ps1` and `UnifiedCanaryRunner.java`**:
  isolated opt-in three-mode production fixture runner, static media audit and
  diagnostic preservation. Not a production-queue or editorial test.
- **`tools/qwen3_tts.py`**: canary environment guard prevents spawning a
  persistent helper when not already running.
- **`web/command-center/app.js` and `SelfTest.java`**: fix prior source
  contract and retry/failure-display regressions.

## Added executable tests

- `autonewsroller.commandcenter.ControllerRecoverySelfTest`:
  claim and owner rejection, durable upload, lost upload and completion
  acknowledgements, restart, manual posting, RE-MAKE, expired lease and
  superseding worker. Uses synthetic media and manifest only.
- `autonewsroller.orchestration.ProductionServiceReadinessSelfTest`:
  loopback mock Ollama tags, absent model, invalid URL and service outage
  classification, no inference calls.
- `autonewsroller.orchestration.ProductionStageTelemetrySelfTest`:
  append-only structured event counts and nonnegative durations.
- `tools/test_production_failures.py`: recorded timing and percentile
  fixture, legacy record compatibility.
- `tools/test_canary_helper_guard.py`: no helper spawned during canary.
- Java and Python tests are wired into existing cross-platform CI; the
  Windows canary script is checked for PowerShell syntax.

## Validation constraints and outstanding work

- GitHub-hosted CI is available. Local repository checkout, user workstation
  GPU, current production queues and private archive are not available to
  this engineering environment. Local compilation, full live media generation
  and production-mode benchmarking are **NOT RUN** here; report exact-head
  GitHub CI results separately.
- Recovery tests are isolated **controller state** tests; the complete 20-case
  crash/OS-process/HTTP fault matrix, distributed fencing under real network
  loss, detailed per-media-stage benchmark and visual safety calibration are
  still open.
- The canary runner executes the actual local pipeline when explicitly
  requested on a machine with pre-existing services. It is not a remote
  worker/controller end-to-end acceptance exercise.
- Stage telemetry describes measured attempts only, not GPU energy draw.
  No performance reduction can be claimed without before/after logs on
  comparable jobs.
- The server checks hashes and version-bound worker manifests. Independent
  controller-side rescan/attestation hardening against a compromised worker
  is an unresolved security release gate.
- Human review and external approvals remain mandatory. No social posting,
  production deployment, branch deletion or main merge is performed.

**Release status: NOT READY.**
