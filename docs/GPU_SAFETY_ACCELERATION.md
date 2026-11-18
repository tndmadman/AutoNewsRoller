# AutoNewsRoller GPU safety acceleration: hardware acceptance and rollback

State at implementation handoff: CPU is the default. The GPU code must NOT be
enabled until the installed RTX 3090 has passed actual CUDA execution,
numerical/decision parity, full-frame encoded-video checks, and memory tests.
The implementation environment had no NVIDIA GPU, local weight files or
licensed video fixtures; no CPU/GPU speedup or deployed GPU service is claimed.

## Safety invariants

NudeNet 3.4.2 retains original 320n ONNX model, NudeDetector detection and
postprocessing, complete frames plus three overlapping crops, exposure classes
and 0.25 threshold. OpenNSFW2 0.19.0 retains original Keras weights, YAHOO
preprocessing, positive probability column 1, image upper/lower regions,
video batch size 8, and thresholds 0.20 review / 0.70 hard block.
Every frame is inspected, the final two-line AI disclosure remains mandatory,
and the controller independently hashes, audits and scans its uploaded MP4.
An incomplete/unknown scan never produces an approval.

## Isolated Windows GPU acceptance

1. Keep GPU mode set to off. Run setup_content_safety_windows.bat on the
   CPU environment. Do not overwrite the production .venv-safety.
2. Run PowerShell tools/setup_content_safety_gpu_windows.ps1 in a separate
   test checkout of the integration branch. It creates .venv-safety-gpu.
   Verify NVIDIA driver, CUDA 12.x, cuDNN 9 and MSVC runtime DLLs. The pinned
   onnxruntime-gpu==1.20.1 is intended for CUDA 12 + cuDNN 9. ORT 1.27+
   defaults to CUDA 13 packages on PyPI. Do not upgrade without retesting.
   Documentation: https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html
3. Export the original OpenNSFW2 model with:
   .venv-safety-gpu/Scripts/python.exe tools/export_opennsfw2_onnx.py --output output/runtime/models/opennsfw2-0.19.0.onnx
   The isolated GPU requirements pin tf2onnx 1.17.0 and ONNX 1.19.0; conversion must still be verified against original weights. Export may fail if Keras 3 and tf2onnx versions are incompatible. An
   export.json provenance report is NOT a parity PASS report. Do not
   download substitute model weights.
4. Create images.txt and videos.txt listing licensed, consented fixture
   files one per line. Include at least 25 heterogeneous still images and
   real encoded videos with at least 100 total decoded frames, including
   borderline classification/disclosure examples, negative controls and
   varied camera scenes. Never commit private fixture media or model weights.
5. Run actual CUDA parity on the GPU host:
   .venv-safety-gpu/Scripts/python.exe tools/validate_safety_gpu_parity.py --images-list images.txt --videos-list videos.txt --context-onnx output/runtime/models/opennsfw2-0.19.0.onnx --licensed-fixtures-confirmed --output output/runtime/gpu_safety_parity.json
   Check model hashes, actual CUDA nodes for BOTH classifiers, NudeNet
   exposure/box/score and decision agreement, OpenNSFW2 probability agreement,
   complete frame coverage, and threshold flips. The script fails closed
   on discrepancies and writes PASS only if all automated checks pass.
   Calibrate false-negative accuracy separately with permitted labeled data.
6. On actual 60-90 second, configured-resolution and configured-FPS encoded
   MP4 files, run at least five cold CPU and CUDA executions per model:
   python tools/benchmark_safety_video.py --backend cpu --python .venv-safety/Scripts/python.exe --video VIDEO.mp4 --runs 5 --out cpu-bench.json
   python tools/benchmark_safety_video.py --backend cuda --python .venv-safety-gpu/Scripts/python.exe --video VIDEO.mp4 --parity-manifest output/runtime/gpu_safety_parity.json --context-onnx output/runtime/models/opennsfw2-0.19.0.onnx --runs 5 --out cuda-bench.json
   Measure warm persistent runs separately; the CLI harness starts models
   fresh for each run, and does NOT measure HTTP upload-to-approval wait.
   Record GPU VRAM, CPU load, actual frames, p50, p95 and max.
7. Verify 99%-to-100% controller canaries in all three video modes and
   malformed/corrupted video; simulate scanner kills, stale claims, model
   corruption, DLL failures, low VRAM and GPU lock contention. Confirm no
   unsafe, review, or scanner-unavailable outcome can publish. Test with
   Ollama, ComfyUI and TTS loaded under representative peak memory use.
8. Only after reviewed hardware results and explicit deployment approval,
   configure matched worker/controller settings and restart during authorized
   maintenance. Do not merge this PR on the strength of CI smoke alone.

## One-click hardware activation
After pulling this branch and stopping the controller and worker, run
`activate_gpu_safety_windows.bat` on the actual RTX 3090 host. It creates the
isolated GPU venv, exports the original OpenNSFW2 weights, requires rights
confirmation for local user-generated fixtures, validates actual CUDA nodes and
CPU/GPU decisions, benchmarks a short encoded video, then writes
`output/runtime/safety_gpu_settings.properties` **only after all gates pass**.
The project automatically loads this ignored override at restart. It does not
change tracked `defaults.txt` or the pinned CPU venv.

The one-click path locates original ComfyUI illustration files and one generated
MP4 in `output/`. If fewer than 25 distinct generated illustrations exist, the
operator can provide `-ImagesList` and `-VideosList` to the PowerShell script
using owned/licensed fixtures. Use a heterogeneous, including borderline,
licensed corpus before trusting parity as sufficient safety evidence. Synthetic
or narrow all-negative fixtures are insufficient for production validation.
A partial/failing test **never** enables GPU mode. The batch file remains open
with PAUSE to show the error and summary. It refuses to run while the live Java
controller or worker is active, preventing interference with current jobs.
GPU selection is `prefer` only after PASS; hardware errors fall back to the same
complete CPU checks with an eight-thread budget. Actual model verdicts never
trigger automatic fallback.

## Configuration and rollback

- contentSafetyGpuMode=off|prefer|require. Default off; prefer allows CPU
  fallback after provider failures; require blocks without GPU. Unsafe and
  review verdicts never trigger a fallback.
- contentSafetyGpuPython=auto -> separate .venv-safety-gpu interpreter.
- contentSafetyGpuDevice=0 (valid range 0..15).
- contentSafetyGpuReserveMiB=4096 (valid 1024..16384). It tests free
  memory at GPU model initialization, NOT an actual CUDA memory reservation.
- contentSafetyGpuWaitSeconds=120 (valid 1..600), bounded shared lock wait.
- contentSafetyGpuParityManifest and contentSafetyGpuContextOnnx are
  absolute or root-relative paths.
- contentSafetyScannerPersistent=true (resident models; required for GPU).
- contentSafetyScannerStartupSeconds=180 (valid 5..900).
- Existing video/image scan timeouts still bound full scans.

The ComfyUI and scanner processes share output/runtime/gpu_ai_lane.lock
when on the same checkout. Separate working copies/hosts require an
external agreed lock or operational serialization. This implementation
does not automatically unload a resident GPU model under ComfyUI VRAM
pressure. Persistent GPU memory under SDXL/Ollama/TTS load is an
additional hardware acceptance blocker until measured and resolved.

For immediate rollback set contentSafetyGpuMode=off on worker and
controller; keep all mandatory safety checks active. If the resident
host misbehaves, also set contentSafetyScannerPersistent=false; this
restores original direct Python CPU invocation. The original pinned
.venv-safety is not modified. Never remove job data, uploaded video,
saved sidecars or approval history during rollback.

Telemetry: output/runtime/safety_scan_metrics.jsonl records per-detector
wall, decoder and inference/preprocessing durations. Authenticated GET
/api/safety/progress shows live controller phase, exact frame counts,
backend and PID; the UI continues to display the overall job at 99%
until independent controller approval. Telemetry cannot authorize media.

Remaining unverified before GPU release: actual parity on a licensed
corpus, exact OpenNSFW2 ONNX conversion and hash, real CUDA/cuDNN node
execution, memory contention, CPU/GPU benchmark on full encoded clips,
and end-to-end controller 99%-to-100% speed gain. Report these as
BLOCKED, not completed, until machine results exist.
