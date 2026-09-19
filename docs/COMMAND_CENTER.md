# AutoNewsRoller Command Center

## What it is

The Command Center is the long-running control plane for AutoNewsRoller.

It is a dependency-light Java HTTP service that can run:

- on the same Windows machine as the GPU worker;
- on a headless Linux server;
- on a small always-on mini PC or VM while one or more GPU workers connect remotely.

The controller does not need Ollama, Kokoro, Qwen3-TTS, ComfyUI, FFmpeg, or an NVIDIA GPU just to watch news and operate the dashboard.

Its core jobs are:

1. scan the RSS source pack continuously;
2. keep the complete story pool, including stories that did not verify;
3. cluster and verify stories;
4. calculate the existing AutoNewsRoller story score;
5. expose the story board in a browser;
6. persist MAKE / HOLD / NOT WORTH / AUTO decisions;
7. maintain the production queue;
8. allow remote workers to claim verified stories;
9. receive live worker telemetry and pipeline stage updates;
10. receive completed MP4 files back from workers;
11. keep a controller-side video archive.

## Architecture

Typical headless deployment:

    HEADLESS LINUX CONTROLLER
      RSS scanner
      story clustering
      source verification
      persistent queue
      web dashboard
      SSE event stream
      video archive
            |
            | HTTP API
            v
    WINDOWS RTX WORKER
      Ollama
      Kokoro
      Qwen3-TTS fallback
      optional ComfyUI
      FFmpeg / NVENC
            |
            | finished MP4 + sidecar
            v
    HEADLESS CONTROLLER

The controller can continue scanning and queueing while the GPU machine is offline.

When the worker reconnects, it claims the highest-scoring queued story.

## Windows all-in-one mode

Run:

    watch_news_windows.bat

or:

    command_center_windows.bat

The first argument is the full RSS scan interval in minutes.

Example:

    watch_news_windows.bat 10

The launcher:

1. compiles Java;
2. launches a local GPU worker in another command window;
3. opens the browser to http://127.0.0.1:8787;
4. runs the Command Center in the foreground;
5. starts a full RSS scan;
6. repeats the scan on the configured interval.

This replaces the old endless BAT loop as the normal watch experience.

## Headless Linux quick start

Requirements for the controller:

- JDK 21+
- curl is recommended because the RSS fetcher can use it as a TLS fallback
- no desktop environment
- no Python models required
- no NVIDIA GPU required

From the repository:

    bash build_linux.sh
    bash run_command_center_linux.sh --host 0.0.0.0 --port 8787 --scan-minutes 10 --auto-queue

Then open:

    http://SERVER-IP:8787

If the controller is reachable beyond localhost, configure a token.

Example:

    export AUTONEWS_TOKEN='use-a-long-random-value'
    bash run_command_center_linux.sh --host 0.0.0.0 --port 8787

The browser will ask for the token when the API returns HTTP 401.

The token is stored in that browser's localStorage after it is entered.

## Remote Windows GPU worker

On the GPU machine:

    set AUTONEWS_TOKEN=the-same-token
    run_gpu_worker_windows.bat http://SERVER-IP:8787 RTX3090-WORKSTATION

The token can also be supplied through the AUTONEWS_TOKEN environment variable permanently using normal Windows environment configuration.

The worker continually:

1. sends a heartbeat;
2. reports system telemetry;
3. asks for the next job;
4. waits if the queue is empty;
5. reconstructs the story candidate received from the controller;
6. runs the normal AutoNewsRoller media pipeline;
7. streams stage updates to the controller;
8. uploads the approved MP4;
9. uploads provenance metadata with completion;
10. asks for the next job.

## Worker telemetry

The worker reports values available from Java and nvidia-smi.

Dashboard telemetry can include:

- hostname;
- worker state;
- current job;
- CPU load;
- process CPU load;
- system memory;
- JVM memory;
- NVIDIA GPU name;
- GPU utilization;
- VRAM used;
- VRAM total;
- GPU temperature.

If nvidia-smi is unavailable, the worker remains usable but NVIDIA telemetry is shown as unavailable.

## Story board states

### DISCOVERED

The story was found and clustered, but it did not pass current verification.

MAKE VIDEO is intentionally blocked in this state.

The dashboard shows the verification reason, such as only one independent source.

### VERIFIED

The story passed independent-source verification or a configured authoritative-primary exception.

It is eligible to be queued.

### QUEUED

The story is waiting for a worker.

A queue can remain populated while every GPU worker is offline.

### PRODUCING

A worker claimed the job.

The controller shows its current stage and progress.

### HOLD

Manual decision to keep the story visible without auto-queueing it.

A later scan can update its sources and verification data, while the HOLD decision remains.

### SKIPPED

Manual NOT WORTH decision.

The controller keeps the record but does not automatically return it to production.

### FAILED

A worker attempted the job and failed.

MAKE VIDEO can explicitly requeue a still-verified failed story.

### COMPLETE

The remote worker uploaded the final MP4 and marked the job complete.

### COMPLETE_HISTORY

The scanner recognized a story fingerprint that was already present in AutoNewsRoller's generated-story history.

## Story controls

### MAKE VIDEO

Queues the story immediately.

It overrides the automatic worthiness threshold.

It does not override factual verification.

If the story is still unverified, the controller returns a conflict response and the UI explains that it should be held while waiting for confirmation.

### HOLD

Prevents automatic queueing but keeps the story active on the board.

### NOT WORTH

Marks the story skipped.

### AUTO

Returns the story to automatic controller decisions.

If it is verified, meets the automatic threshold, and queue capacity is available, it can immediately return to the queue.

## Automatic queueing

Defaults:

    commandCenterAutoQueue=true
    commandCenterAutoThreshold=0.68
    commandCenterMaxQueued=12

A verified story is auto-queued when:

- decision is AUTO;
- it has not already been generated;
- score meets or exceeds the threshold;
- queue plus active work has not reached maxQueued.

Manual MAKE can queue a verified story below the threshold.

## Worthiness score

The dashboard uses the existing StoryRanker score.

The current score is based on configured weights such as:

- freshness;
- independent source count;
- source quality heuristic;
- novelty weight;
- video suitability.

It is a production-priority score, not a factual-truth probability.

Verification remains a separate gate.

## Political source baseline

The dashboard's publisher-level LEFT / CENTER / RIGHT / UNKNOWN bars use attributed external metadata from `config/source_bias.json`.

The bundled file currently identifies AllSides as the provider and stores an as-of date, provider URLs, original provider labels, and confidence metadata when available.

For visualization only:

- Left and Lean Left map into the left bar;
- Center maps into center;
- Lean Right and Right map into the right bar;
- unrated or unsupported publishers remain unknown.

The original provider label remains visible on the card. Publisher baseline and article-level framing are intentionally separate measurements.

See the later "Political source baseline and article framing" section for the worker-side Ollama analysis flow.

## Feed radar

The dashboard receives feed-state changes while a scan is running.

Feed states include:

- SCANNING
- OK
- FAILED

The animated radar places feed points deterministically from their URL so their approximate screen locations stay stable between refreshes.

The feed-health panel also shows publisher, category, state, and entry count.

## Live event stream

Endpoint:

    GET /api/events

Transport:

    Server-Sent Events (SSE)

Events include:

- feed state;
- scan state;
- story state;
- worker heartbeat;
- completed video.

The browser also polls /api/state as a fallback so temporary SSE disconnects do not leave the dashboard stale.

## Main API endpoints

### Health

    GET /api/health

### Complete controller state

    GET /api/state

### Live events

    GET /api/events

### Force a scan

    POST /api/scan

### Story detail

    GET /api/stories/{storyId}

### Story action

    POST /api/stories/{storyId}/action

JSON:

    {"action":"MAKE"}

Other values:

- HOLD
- SKIP
- AUTO

### Worker heartbeat

    POST /api/workers/heartbeat

### Claim job

    GET /api/jobs/claim?worker=WORKER_ID

Returns HTTP 204 when no job is waiting.

### Progress

    POST /api/jobs/{jobId}/progress

### MP4 upload

    PUT /api/jobs/{jobId}/video

The original filename is sent in X-Filename.

### Complete job

    POST /api/jobs/{jobId}/complete

### Fail job

    POST /api/jobs/{jobId}/fail

### Download controller copy

    GET /videos/{filename}

## Authentication

When AUTONEWS_TOKEN or --token is configured, API and video endpoints require the same token.

Workers use:

    X-AutoNews-Token: ...

The dashboard can use:

    http://server:8787/?token=...

or enter the token through the KEY button.

Avoid placing a long-lived token in browser history if the machine is shared; using the prompt is preferable.

The static HTML/CSS/JS can load without a token, but protected API calls will fail until a token is entered.

## Network security

Do not expose the raw Command Center port directly to the public internet.

Preferred remote-access patterns:

- private LAN;
- WireGuard/Tailscale-style VPN;
- authenticated reverse proxy with TLS;
- firewall rules limited to trusted networks.

The built-in token is an application-level control, not a replacement for TLS on hostile networks.

## Persistent state

Controller state:

    data/command_center_state.json

It preserves:

- stories;
- decisions;
- queue state;
- feed state;
- completed-video metadata;
- last scan summary.

Transient worker heartbeats are not relied on for job persistence.

Controller MP4 archive:

    output/command_center/videos/

Scan diagnostics:

    output/command_center/scans/

Remote worker local work:

    output/remote_worker/

## systemd deployment

Templates:

    deploy/systemd/autonewsroller-command-center.service
    deploy/systemd/autonewsroller.env.example

Typical layout:

    /opt/autonewsroller

Example setup outline:

    sudo useradd --system --home /opt/autonewsroller --shell /usr/sbin/nologin autonewsroller
    sudo mkdir -p /opt/autonewsroller
    sudo chown -R autonewsroller:autonewsroller /opt/autonewsroller

Place/clone the repository there, then build:

    sudo -u autonewsroller bash /opt/autonewsroller/build_linux.sh

Install environment file:

    sudo cp deploy/systemd/autonewsroller.env.example /etc/autonewsroller.env
    sudo chmod 600 /etc/autonewsroller.env

Edit the token.

Install service:

    sudo cp deploy/systemd/autonewsroller-command-center.service /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable --now autonewsroller-command-center

Inspect:

    sudo systemctl status autonewsroller-command-center
    sudo journalctl -u autonewsroller-command-center -f

The service template expects writable data and output directories under /opt/autonewsroller.

## Command-line modes

Controller:

    java -cp build/classes autonewsroller.Main --command-center

Useful controller flags:

    --host 0.0.0.0
    --port 8787
    --scan-minutes 10
    --auto-queue
    --auto-threshold 0.68
    --max-queued 12
    --token VALUE
    --no-initial-scan

Worker:

    java -cp build/classes autonewsroller.Main --worker --controller-url http://SERVER:8787 --worker-id NAME

Useful worker flags:

    --controller-url URL
    --worker-id NAME
    --token VALUE

## Current limitations

Command Center production jobs now request optional ComfyUI imagery by default. If no explicit imageCheckpoint is set, comfyAutoPickCheckpoint=true allows the worker to select the first checkpoint advertised by ComfyUI. A ComfyUI failure remains non-fatal and falls back to procedural cards.

The story card and video archive report the actual media path used:

- TTS engine actually used: Kokoro or Qwen3 fallback;
- selected voice;
- ComfyUI success/fallback detail;
- checkpoint used;
- count of ComfyUI-generated images included in the completed video.

The first command-center implementation deliberately focuses on the complete controller/worker loop rather than every possible visual.

Current limitations include:

- intermediate ComfyUI images are not yet streamed to the controller while a job is running;
- the controller receives the final MP4 only after local worker audit succeeds;
- the browser progress bar is stage-based rather than FFmpeg frame-by-frame progress;
- political source labels require an external configured dataset;
- workers currently poll for jobs instead of using a long-lived worker WebSocket;
- there is no user-account/role system; one shared API token protects the controller;
- controller state uses JSON persistence rather than SQLite;
- automatic publishing to social platforms is still separate roadmap work.

These choices keep the initial distributed architecture dependency-light and usable on a small headless server.

## Validation

The cross-platform workflow includes:

- Windows Java compile;
- Windows self-test;
- Windows fixture dry run;
- Linux Java compile;
- Linux self-test;
- Linux fixture dry run;
- headless Linux Command Center startup with no initial live scan;
- HTTP /api/health check;
- HTTP /api/state check;
- dashboard HTML fetch check.

This verifies the controller can actually start headlessly on Linux rather than only compiling there.


## Political source baseline and article framing

The Command Center now separates two different measurements.

### Publisher baseline

`config/source_bias.json` contains attributed publisher-level political-bias metadata.

The bundled baseline uses AllSides Media Bias Ratings for supported publishers and stores:

- the dashboard bucket: left, center, right, or unknown;
- the provider's original classification such as Lean Left or Right;
- the provider's confidence label when available;
- a direct source-rating URL;
- an as-of date.

The three dashboard bars collapse Lean Left into left and Lean Right into right only for visualization. The original provider label remains visible on the story card.

A publisher baseline is not treated as a classification of every article from that publisher.

### Article framing

Political stories are automatically queued for article-level framing analysis.

The controller remains lightweight: it does not run Ollama itself. When no video is waiting, a connected worker claims a `POLITICAL_ANALYSIS` job and uses its local Ollama model.

The analyzer examines only supplied headline/description/body text and is instructed not to use publisher identity as evidence.

Per story it returns:

- political relevance;
- overall classification: left, center, right, mixed, uncertain, or not political;
- confidence;
- a short framing summary;
- per-article classification and confidence;
- short observable framing signals.

Center is a framing category, not a truth or credibility score.

The UI shows publisher baseline and article framing as separate panels.

Video jobs always take priority over framing-analysis jobs.

### Manual analysis

Any story can be analyzed or reanalyzed from its card with:

    ANALYZE FRAMING

API:

    POST /api/stories/{storyId}/analyze-bias

Worker result endpoints:

    POST /api/jobs/{storyId}/bias-complete
    POST /api/jobs/{storyId}/bias-fail

### Worker-side model settings

Defaults:

    politicalAnalysisModel=llama3.1:8b
    politicalAnalysisOllamaUrl=http://127.0.0.1:11434/api/generate

These are worker-side settings. A headless controller does not require Ollama merely to host the dashboard or scan RSS feeds.
