# Windows GPU Worker Web Console

## Purpose

The worker console is **not** the controller Command Center. The Command Center (normally
`http://127.0.0.1:8787`) owns the global queue, story decisions, leases, video uploads,
publication state and retry policy. The worker console (normally
`http://127.0.0.1:8788`) runs **on the machine doing the GPU work**, displays this
worker's actual local state and provides local lifecycle controls.

Both use Java 21 and dependency-free HTML/CSS/JavaScript. The worker interface binds
to `127.0.0.1` only. It remains available when the controller or the GPU is offline.

## Launch on Windows

1. Install JDK 21, FFmpeg/FFprobe and the optional AI services appropriate to your setup.
2. Set `AUTONEWS_TOKEN` in the **environment** if your controller requires it. Do not
   paste this credential into a browser or settings file.
3. Double-click `run_gpu_worker_ui_windows.bat` (or run it from CMD):
   `run_gpu_worker_ui_windows.bat http://127.0.0.1:8787 MY-GPU-01`
4. Compilation completes first; the browser normally opens automatically at
   `http://127.0.0.1:8788`. If it does not, open that URL manually.
5. The worker starts automatically unless you disable *Auto-start* under Settings.

`command_center_windows.bat` starts the controller and the local worker console
using the existing hostname as worker ID. It checks for an already-running worker
console on port 8788, rather than deliberately starting another worker instance.

**Legacy headless use remains available**:
`run_gpu_worker_windows.bat http://127.0.0.1:8787 MY-GPU-01` or
`run_gpu_worker_linux.sh http://127.0.0.1:8787 MY-GPU-01`.

Do **not** launch a separate headless worker with the same worker ID as a running
console-managed worker. An OS-held identity lock under `output/runtime/` prevents
two workers in the same installation from simultaneously using the same ID.
Separate repository clones and separate hosts do not share this lock; choose
globally distinct worker IDs. Job claims are made only by `RemoteWorker`, not the UI.

## Worker lifecycle

The console starts one `RemoteWorker` instance with one claim loop and one heartbeat
scheduler. Each completed stop allows a **new instance**; a terminated heartbeat
scheduler is never reused. The console HTTP server stays up after worker shutdown.

- **START** acknowledges the request before connection is confirmed. Only one active
  worker thread can be started through the console.
- **PAUSE** stops *future claims*. An in-flight claim can still arrive and is
  processed through its controller completion/failure callback; the active job is
  never abandoned. The heartbeat continues.
- **RESUME** re-enables claiming on the existing worker; it does not create a new
  heartbeat thread.
- **DRAIN / STOP** prevents new claims, finishes already-claimed work, uploads any
  completed video, sends the controller completion/failure callback and then stops.
  A drain request is not a fully stopped confirmation.
- **SAFE RESTART** requests drain and then creates a fresh worker at the safe
  lifecycle boundary. Controller URL and worker ID saved in Settings become effective
  with that restart.
- **DISCONNECTED** means recent contact with the controller has not been confirmed.
  Active rendering and controller link health are tracked separately.

The controller config default is a **180-second lease**. While a long render runs,
the worker still sends 10-second heartbeats with the active worker/job/claim ID.
These protect the controller's video job leases; political-analysis work uses its
existing worker-assignment lease behavior. Loss of contact longer than the lease
can still cause controller-side recovery or a retried claim; confirm the authoritative
controller state before manually re-queuing jobs.

The web interface has **no emergency kill button**. It does not kill outside Python,
Ollama, ComfyUI, Qwen or FFmpeg processes. Force-closing the CMD window or terminating
Java from Task Manager is *not* a graceful stop and may leave a controller lease
waiting for expiration or retry.

## Pages and sources

- **Overview**: lifecycle, last successful controller contact and heartbeat, active
  job, most recent issue, cached host/GPU sample and operator controls.
- **Hardware**: Java OperatingSystemMXBean, JVM memory and optional NVIDIA
  `nvidia-smi` output; sampled once per six seconds for all browser clients and
  heartbeats. Values are unavailable when not reported, not silently zeroed.
- **Active Job**: real claim identity, current pipeline stage/detail, controller
  upload acknowledgment status and the current browser session's stage events.
  No made-up progress percentages, frame counts or ETAs.
- **History**: at most 100 locally recorded job summaries, persisted atomically
  to `output/runtime/worker-ui-jobs.json`. The global controller remains authoritative.
- **Live Logs**: bounded structured lifecycle, stage, controller and worker events
  with filtering and display pause. The *Clear display* action clears only the
  browser's current display, never production/source log files.
- **Diagnostics**: manual timeout-bounded checks for Java, NVIDIA,
  FFmpeg, FFprobe, configured service endpoints, Python environments, helpers,
  output write access and free disk. Optional deep diagnostics probe PyTorch
  CUDA visibility and configured Ollama model/Comfy checkpoint inventories
  without loading large models. An optional component warning does not mean the
  entire worker is dead. Opening a page does not start a model.
- **Settings**: local connection, port, auto-start, browser launch,
  log-retention cap and refresh frequency. Inherited encoder/TTS/ComfyUI
  properties are display-only and remain controlled by `defaults.txt`/controller.

Runtime log sources for individual productions are written under
`output/remote_worker/<job>_<timestamp>/`: `events.jsonl`,
`debug.log`, and `runtime/worker_001.log`, when emitted by that pipeline.
Full production log files remain on disk; the in-browser event terminal provides
a bounded structured operational view, **not** the complete text of every debug log.

## HTTP interfaces

All endpoints are available on the loopback server only:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/worker/health` | Local service availability |
| GET | `/api/worker/status` | Worker execution/link status plus last metrics |
| GET | `/api/worker/metrics` | Last cached hardware sample and age |
| GET | `/api/worker/jobs` | Bounded worker-local ledger |
| GET | `/api/worker/logs` | Bounded structured log history |
| GET | `/api/worker/diagnostics` | Cached diagnostic results |
| GET | `/api/worker/settings` | Safe non-secret local settings |
| GET | `/api/worker/events` | Bounded SSE stream, reconnectable |
| POST | `/api/worker/control` | start / pause / resume / drain / stop / restart |
| POST | `/api/worker/settings` | Validate and atomically persist settings |
| POST | `/api/worker/diagnostics/run` | Queue quick or deep diagnostics (`mode`) |

POST uses JSON and requires `X-Worker-Request: 1`. Requests must use a valid
loopback Host and matching Origin (if supplied). No CORS permissions are granted.
POST size is capped at 16 KiB. Actions return acknowledged intent, **not**
guarantees that asynchronous work has finished.

## Safety and remote administration

The console is intentionally local-only; it does not expose job controls to the
LAN/Internet. Never port-forward 8788 publicly. For administration from another
computer use a trusted, authenticated local port forward/tunnel that terminates on
the worker and is accessible only to the operator; keep the console bound to
127.0.0.1. Do not assume a firewall is authentication. Tokens belong exclusively
in `AUTONEWS_TOKEN` and are not returned to browser JavaScript.

`output/runtime/worker-ui-settings.json` contains UI preferences and never
stores controller credentials. It is atomically saved. Editing the local port
requires relaunching Java; changing controller URL/worker ID requires safe worker
restart. The browser cannot execute arbitrary OS commands or browse the filesystem.

## Recovery and troubleshooting

- **Page unavailable**: run `build_windows.bat`, check Java 21, inspect the
  CMD error. Port 8788 may be occupied by another process. Do not kill a
  production worker merely to reclaim the port.
- **Worker disconnected**: verify controller URL and authentication environment,
  ensure port 8787 is reachable, and inspect the controller Command Center.
- **Worker paused**: click Resume to resume claiming; current work continues
  even while the claim loop is paused.
- **Worker draining**: it can remain in this state while a long render/upload
  completes or a timed-out controller call returns. Watch Active Job/Logs.
- **Restart with new settings**: save, then Safe Restart, then confirm effective
  worker ID/controller in Status. Use a different worker ID if another process
  is still operating under the original ID.
- **GPU unavailable**: the interface still starts. Run diagnostics, check
  `nvidia-smi` installation/driver and CUDA device availability separately.
- **Stale metrics**: a sample older than 18 seconds is labelled stale.
- **After crash**: the local ledger can contain `RUNNING` entries from an
  unclean termination; these are historical *unknown*, not proof of completion.
  Check controller state before retrying.

## Validation limitations

Software-only CI cannot prove physical CUDA, ComfyUI, voice quality, VRAM or
real FFmpeg productions on your Windows GPU. Those need an operator hardware
acceptance run. The Linux and Windows Java compilation, worker HTTP smoke tests,
Java self-tests and browser tests should be checked on the pull request before
promoting this to production.
