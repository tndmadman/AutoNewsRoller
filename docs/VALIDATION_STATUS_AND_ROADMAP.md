# Validation Status and Roadmap

## Current state

AutoNewsRoller's core pipeline is implemented.

The repository has passed Windows fixture/CI validation, but the full heavyweight production stack has not yet been proven end-to-end on the target local machine.

Use the following status vocabulary:

- Implemented — source code exists.
- Automated validated — fixture/self-test/CI has exercised the behavior.
- Live validated — actual external feed/model/GPU/service path has been exercised successfully on the target machine.

## Implementation history

Original implementation pull request:

- PR #1
- title: Implement automated news-to-video pipeline
- validated head: 326bb53c12a6c2b52caa24f5346b98add17a9420
- merged to main
- merge commit: 6e3f0e3ac4307b5ec51d79d3d5b78ba97a4d3af7

A post-merge Windows workflow also completed successfully on that main commit.

## Historical Windows workflow verification

Feature run:

- workflow run ID: 35461309135
- feature head: 326bb53c12a6c2b52caa24f5346b98add17a9420
- result: success

Post-merge main run:

- workflow run ID: 35461369719
- main head: 6e3f0e3ac4307b5ec51d79d3d5b78ba97a4d3af7
- result: success

The logs were inspected, not merely the green status badge.

## Current cross-platform command-center validation

The workflow now has separate Windows and Linux jobs.

Windows validates:

- JDK 21 compilation;
- Python helper syntax;
- PowerShell dashboard syntax;
- all 20 Java self-tests;
- offline fixture batch target behavior.

Linux validates:

- shell syntax for the Linux launchers;
- JDK 21 compilation;
- all 20 Java self-tests;
- offline fixture batch target behavior;
- headless Command Center process startup;
- GET /api/health;
- GET /api/state;
- serving the browser dashboard HTML.

This verifies that the controller is genuinely headless/cross-platform and not dependent on Windows BAT files.

## Important CI issue that was found and fixed

An earlier Windows workflow appeared green even though javac had failed.

Root problems included:

- response-file path generation on Windows;
- fragile batch error propagation.

Fixes included:

- using PowerShell to enumerate Java files;
- normalizing source paths with forward slashes;
- quoting each source path;
- explicit if errorlevel 1 exit /b 1 checks.

The workflow was rerun and actual logs then showed:

    Java compile passed.

This is why future validation should inspect meaningful output, not only workflow conclusion.

## Current Java self-test coverage

SelfTest currently reports **20 checks**:

1. JSON parser.
2. Expanded source configuration parsing.
3. Expanded source URLs are unique.
4. RSS fixture parsing.
5. RSS redirects and 304 cached-snapshot reuse.
6. Duplicate clustering separates unrelated story.
7. Same event clustered.
8. FactPackage verification.
9. Syndication duplicate does not destroy independent confirmations.
10. Command Center auto-queues a verified high-score story.
11. Remote-worker candidate serialization/reconstruction round trip.
12. Manual MAKE can requeue a still-verified failed story.
13. Authoritative primary-source exception.
14. Malformed feed rejected without process exit.
15. Dry-run script generation.
16. Script JSON/fact validation.
17. TTS fallback state logic.
18. Dashboard event parsing.
19. Output filename collision handling.
20. NVENC probe mode logic.

## Offline fixture batch validation

The Windows self-test runs:

    --dry-run
    --fixture
    --batch-target 1
    --workers 2
    --max-age-hours 24
    --minimum-independent-sources 2
    --duration 60
    --encoder x264

Expected verified result after the concurrency fix:

    Requested workers: 2; effective workers: 2; reason: requested capacity available
    Approved 1 / target 1 after 1 attempt(s)

The key point is exactly one approval for a target of one.

## Batch reservation race that was fixed

Before the reservation fix, multiple workers could race when target=1 and both complete successfully, producing two approved outputs.

BatchCoordinator now tracks:

- approved;
- inFlight;
- target;

under a reservation lock.

A worker only reserves another candidate if:

    approved + inFlight < target

This prevents target overshoot in the normal coordinator.

## Local non-Windows development validation performed during implementation

The implementation was also checked locally for:

- javac compilation;
- Python py_compile;
- Java self-test;
- fixture dry run;
- FFmpeg x264 smoke render;
- VideoAudit on a synthetic non-silent audio/video output.

The FFmpeg smoke render produced a 1080x1920 MP4 and passed the technical audit path.

## What is NOT yet live validated

### Live news discovery

Needs real run against current configured feeds.

Questions to validate:

- do all feed URLs still respond;
- are publication timestamps parsed correctly;
- does article-page enrichment work;
- how often do anti-bot/paywall pages appear;
- does the current 0.52 cluster threshold group stories correctly;
- does minimum-two-sources rule leave enough candidates.

### Live Ollama

Needs:

- real llama3.1:8b generation;
- JSON format reliability;
- retry behavior;
- script quality;
- hallucination review against FactPackage;
- duration quality.

### Kokoro

Needs:

- real .venv-kokoro synthesis;
- each configured voice;
- long narration;
- timing sidecar behavior.

### Qwen3-TTS

Needs:

- CUDA model load;
- actual fallback WAV;
- voice pool validation;
- server persistence;
- shutdown ownership;
- release-gpu;
- reload after release.

### ComfyUI

Needs:

- endpoint compatibility with installed ComfyUI;
- actual checkpoint discovery;
- selected checkpoint load;
- prompt graph compatibility;
- generated file retrieval;
- /free behavior;
- fallback on failure.

### NVENC

Needs a real probe/render on the target FFmpeg build, NVIDIA driver, and GPU.

### Multi-worker stability

Needs a long enough run to expose:

- VRAM pressure;
- Qwen/Comfy contention;
- file-lock issues;
- model reload instability;
- CPU/RAM pressure;
- FFmpeg parallel load;
- worker starvation/deadlock;
- repeated-service crash patterns.

### Distributed Command Center worker handoff

Controller/store/API/worker serialization and headless Linux startup are automated-validated.

Still needs a real two-machine live test with:

- Linux controller on the LAN;
- Windows RTX worker;
- worker heartbeat and NVIDIA telemetry;
- real job claim;
- real Ollama/TTS/Comfy/FFmpeg generation;
- MP4 upload back to Linux;
- controller archive playback;
- worker disconnect/reconnect while jobs remain queued.

### Phone integration

Not implemented yet.

Needs code plus real Windows-to-phone test.

## Highest-priority roadmap

### Priority 0 — live controller/worker LAN proof

The distributed control plane now exists and passes Windows/Linux CI.

Before calling the new architecture live-hardware validated:

1. run the controller on the intended headless Linux box;
2. connect the Windows RTX worker;
3. confirm dashboard CPU/RAM/GPU/VRAM telemetry;
4. queue one verified story with MAKE VIDEO;
5. generate a real MP4;
6. verify live stage progress;
7. verify the MP4 uploads back to the Linux archive;
8. power off/disconnect the worker, queue another story, reconnect, and confirm the queued job is claimed.



### Priority 1 — target-machine smoke test

Run setup_windows.bat.

Then:

    batch_create_news_videos_windows.bat --self-test

Fix any environment-specific dependency failures first.

### Priority 2 — one real video without ComfyUI

Run a live one-video job with encoder auto.

Confirm:

- feed discovery;
- verification;
- Ollama;
- TTS;
- FFmpeg;
- audit;
- final MP4;
- provenance.

### Priority 3 — force Qwen fallback

Deliberately make Kokoro unavailable in a controlled test.

Confirm:

- error is visible;
- Qwen starts;
- CUDA model loads;
- WAV is produced;
- metadata says Qwen3 fallback;
- video audit passes.

Restore Kokoro afterward.

### Priority 4 — ComfyUI live test

Set imageCheckpoint explicitly.

Confirm:

- checkpoint query;
- Qwen VRAM release;
- image generation;
- output download;
- final render;
- fallback behavior when intentionally misconfigured.

### Priority 5 — multi-worker stress test

Run:

- target 5 to 10;
- workers 2, then 4.

Measure hardware behavior.

Do not jump directly to very high concurrency until VRAM use is known.

### Priority 6 — live source-pack health and clustering validation

The default pack has been expanded from the original five feeds to 88 enabled RSS feeds across 21 publisher/source groups.

Next validate the pack on the target machine:

- record which feeds consistently succeed, redirect, return 403/429, or fail TLS;
- disable persistently dead feeds instead of wasting retries;
- measure total scan time;
- review same-event clustering across differently worded publishers;
- tune clustering only from observed false negatives/false positives;
- continue reviewing source terms/licensing for the intended use.

### Priority 7 — per-domain article rate limiter

Implement a shared host-level request controller.

This is more important for linked article pages than RSS feed polling.

### Priority 8 — contradiction detection

Current FactPackage has a disputedClaims field, but verification currently does not populate a meaningful contradiction model.

Add:

- entity/value extraction;
- conflict grouping;
- explicit contested facts;
- script rule requiring attribution or omission.

### Priority 9 — better fact extraction

Current FactExtractor focuses on headline and description sentences.

Use enriched bodyText selectively while avoiding:

- navigation garbage;
- copied instructions;
- overlong copyrighted expression;
- unsupported claims.

### Priority 10 — dynamic novelty scoring

ranking.json includes novelty=0.20, but current discovery passes novelty=1 for all surviving candidates.

Implement novelty against recent story history.

### Priority 11 — notifications and phone transfer

Implement the design in NOTIFICATIONS_AND_PHONE_DELIVERY.md.

Preferred:

- KDE Connect for MP4;
- ntfy for structured push status.

### Priority 12 — publishing integration

Only after generation is stable.

Potential future publishing adapters should be separate from core generation and should require explicit platform configuration/authentication.

## Medium-priority code cleanup

### Make httpRetries authoritative

Currently defaults.txt contains httpRetries=3 while fetchers directly implement three attempts.

Use the config value.

### Make feedRefreshMinutes authoritative

watch_news_windows.bat currently has its own 30-minute default.

Either:

- have Java own watch mode; or
- have the batch file read defaults.txt.

### Make TTS engine config authoritative

ttsPrimary and ttsFallback are currently descriptive values.

If engine order is intended to be configurable, instantiate the chain from config.

### Use exact TTS timing

Prefer Kokoro timing.tsv when available.

For Qwen, add alignment/timestamp support if possible.

### Persist article-page cache

Avoid re-downloading identical linked article pages across runs.

### Honor Retry-After

429 handling should use server-provided delay when available.

### Add domain circuit breaker

Temporarily stop requesting a site after repeated access failures.

## Editorial-quality roadmap

Technical automation alone does not guarantee a good news product.

Future quality gates can include:

- duplicate-topic semantic review;
- named-entity consistency;
- unsupported quote detection;
- disputed-story sensitivity rules;
- synthetic-visual disclosure;
- minimum source quality;
- category-specific policies;
- human review queue for high-risk topics.

## Production readiness definition

A reasonable "production-ready for unattended local generation" bar:

1. 20+ live videos generated across several categories.
2. Zero silent or corrupt outputs.
3. TTS engine metadata always matches actual engine.
4. Qwen fallback demonstrated.
5. Comfy fallback demonstrated.
6. No persistent ComfyUI crashes during stress run.
7. No duplicate story flood across polling cycles.
8. No obvious same-event cluster failures in reviewed sample.
9. Source/site request behavior remains polite.
10. Notifications report both approvals and infrastructure failures.
11. Phone transfer succeeds reliably if enabled.
12. A human sample review finds scripts factually grounded in stored FactPackages.

## Current practical completion estimate

The implementation can reasonably be described as roughly 85-90% complete as a generator architecture.

The remaining work is disproportionately important because it is the operational proof:

- live model integration;
- hardware coexistence;
- source tuning;
- legal/source-pack cleanup for intended use;
- unattended stability;
- delivery/publishing integration.

Do not confuse percentage of code written with percentage of production risk removed.
