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

- **`CommandCenterStore.java`**: durable operator CANCEL for queued/producing work; late worker completion cannot resurrect cancelled jobs, historical versions remain; active lease/claim fencing; persisted upload
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

- `autonewsroller.commandcenter.ControllerHttpRecoverySelfTest`: real loopback HTTP duplicate upload, stale claim, invalid bytes, legacy header rejection and restart of an isolated controller. Synthetic media only.
- `autonewsroller.commandcenter.ControllerRecoverySelfTest`:
  claim and owner rejection, cancellation and late completion, durable upload, lost upload and completion
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
  Windows canary script is checked for PowerShell syntax and exercised in dry
  mode on Windows CI. This does not prove a GPU-generated video.

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

## Controller-owned approval gate — follow-up change

- HTTP `/api/jobs/{id}/complete` first resolves the exact controller-stored
  upload identity under the current worker claim and production epoch.
- Before a new non-dry-run completion, the controller executes both mandatory
  detector processes over the uploaded MP4, verifies the complete decoded-frame
  and AIGC evidence, and audits encoded video, audio, frame rate, resolution,
  synchronization and audible audio with FFprobe/FFmpeg.
- The controller replaces worker approval metadata with its own
  `CONTROLLER_INDEPENDENT_RESCAN` result, binds it to the received SHA-256 and
  persists the result with the completed version. Repeated identical completion
  acknowledgements reuse the already committed identity without rescanning.
- A missing scanner, detector failure, bad media, invalid policy or changed
  bytes blocks completion. The upload may remain staged, but is not publishable.
- `ControllerHttpRecoverySelfTest` now sends a forged SAFE completion and
  checks that a scanner-less isolated controller rejects it and does not serve
  the unapproved MP4. This fixture is not a genuine detector scan.
- **Validation:** This implementation must pass exact-head Windows/Linux CI.
  Neither live detector execution nor real hardware safety approval is claimed
  by this code-only change. Historical manifests, controller restart during
  rescanning, and media-service calibration remain separate release gates.

### Independent controller scan latency and concurrency

- The real worker now uses a separate completion HTTP timeout calculated from
  both mandatory controller scan time budgets plus encoded-media overhead.
  Heartbeats continue while a completion response is pending.
- Concurrent completion requests for the same job are serialized through a
  bounded stripe of controller locks. An identical replay checks the durable
  committed identity and does not repeat scanner work or append a new version.
- The controller checks encoded MP4 validity before spending detector time on
  malformed or silent media. The HTTP fixture asserts the completion timeout
  budget covers two configured detector timeouts.
- Live latency, controller interruption during scanning and end-to-end
  restart tests remain unverified until exact-head CI and hardware acceptance.

### Historical worker-only approval remains non-publishable

- Manual upload-status changes and HTTP MP4 serving now require the persisted
  `CONTROLLER_INDEPENDENT_RESCAN` approval origin. A legacy worker-provided
  approval manifest is not sufficient, even when its hash and detector fields
  pass structural checks. Existing archive records and media bytes are retained.
- The isolated HTTP fixture deliberately creates synthetic valid-looking
  worker scan metadata and verifies it cannot unlock MP4 serving. The
  state-machine fixture uses a synthetic controller-origin marker solely to
  exercise accounting and does not prove that real detectors ran.
- A separate reviewed re-verification procedure is required before historical
  worker-only artifacts can become newly publishable. Do not silently grant
  historic approvals during state migration.
