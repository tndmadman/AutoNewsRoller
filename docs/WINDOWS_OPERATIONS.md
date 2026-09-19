# Windows Operations Guide

## Scope

This document describes how the current repository is intended to be built and operated on Windows.

The project is Windows-first, but most core Java logic is portable. The batch launchers, setup flow, dashboard, isolated virtual-environment paths, and expected local tooling are Windows-specific.

## Prerequisites

Recommended:

- Windows 10 or Windows 11
- JDK 21+
- Python 3.12
- FFmpeg
- ffprobe
- Ollama for normal non-dry script generation
- NVIDIA GPU and working CUDA environment for Qwen3-TTS
- optional ComfyUI service for generated imagery

The setup script checks:

- Python is callable;
- javac exists;
- ffmpeg exists;
- ffprobe exists.

If Ollama is not found, setup prints a warning rather than immediately failing, because dry-run validation can operate without live Ollama generation.

## setup_windows.bat

Run:

    setup_windows.bat

The script:

1. switches to the repository directory;
2. tries to select Python 3.12;
3. verifies Python;
4. verifies javac;
5. verifies ffmpeg;
6. verifies ffprobe;
7. warns if Ollama is not found;
8. creates isolated Python environments if missing;
9. installs Kokoro dependencies;
10. imports Kokoro, soundfile, and numpy as a sanity check;
11. installs CUDA PyTorch/torchaudio for Qwen;
12. installs Qwen TTS dependencies;
13. verifies torch.cuda.is_available();
14. prints the detected CUDA GPU name;
15. calls build_windows.bat.

Virtual environments:

    .venv-news
    .venv-kokoro
    .venv-qwen3-tts

The current setup script creates .venv-news but does not currently install an additional requirements file into it.

## Python isolation

Kokoro and Qwen are deliberately separated.

Benefits:

- model-specific package conflicts are less likely;
- CUDA-specific Qwen dependencies do not pollute the Kokoro environment;
- failures can be diagnosed separately;
- the system Python installation is not intentionally modified globally.

## build_windows.bat

This is the Java compiler wrapper.

It:

1. changes to the repository directory;
2. creates build\classes if needed;
3. uses PowerShell to recursively enumerate src\**\*.java;
4. writes quoted source paths to build\sources.txt;
5. invokes javac;
6. exits nonzero if enumeration or compilation fails;
7. prints "Java compile passed." on success.

Effective compile command:

    javac -encoding UTF-8 -d build\classes @build\sources.txt

It does not:

- install Python packages;
- start Ollama;
- download Ollama models;
- start ComfyUI;
- run Kokoro;
- run Qwen;
- render a video.

## Why build_windows.bat uses PowerShell for sources.txt

An earlier Windows CI iteration exposed a path/response-file problem where a generated source-list path lost backslashes and javac failed.

The current PowerShell enumeration writes each full Java source path with normalized forward slashes and quotes.

The batch file also uses explicit:

    if errorlevel 1 exit /b 1

instead of relying on fragile chained command/errorlevel behavior.

This Windows-specific build-path issue was caught by inspecting actual CI logs before the implementation was merged.

## Self-test

Run:

    batch_create_news_videos_windows.bat --self-test

Current self-test flow:

1. compile Java;
2. Python syntax-compile:
   - tools\fetch_news.py
   - tools\article_extract.py
   - tools\kokoro_tts.py
   - tools\qwen3_tts.py
   - tools\qwen3_tts_server.py
3. parse tools\batch_dashboard.ps1 with PowerShell's parser;
4. run Java SelfTest;
5. run an offline fixture dry-run with target 1 and 2 requested workers.

The fixture dry-run command is equivalent to:

    java -cp build\classes autonewsroller.Main --dry-run --fixture --batch-target 1 --workers 2 --max-age-hours 24 --minimum-independent-sources 2 --duration 60 --encoder x264

It intentionally avoids giant model downloads and live GPU inference.

## One-video launcher

Run:

    run_news_video_windows.bat

It:

1. builds Java;
2. launches autonewsroller.Main;
3. supplies --batch-target 1;
4. forwards any additional arguments.

Examples:

    run_news_video_windows.bat --category technology

    run_news_video_windows.bat --category world --duration 60 --encoder auto

    run_news_video_windows.bat --dry-run --batch-target 5 --workers 4

The last example overrides the launcher's default target of one because Main receives the later argument value through its parsed argument map.

## Interactive batch launcher

Run:

    batch_create_news_videos_windows.bat

Or supply target/workers positionally:

    batch_create_news_videos_windows.bat 30 5

It prompts for:

- target approved videos;
- parallel workers;
- category;
- maximum article age;
- minimum independent sources;
- target video duration;
- encoder;
- optional ComfyUI;
- Ollama warm/unload preference.

It then:

1. compiles Java;
2. creates a timestamped batch directory;
3. launches the PowerShell dashboard in another window;
4. runs the Java batch.

## Dashboard

tools\batch_dashboard.ps1 reads:

    <batch>\runtime\events.jsonl

It refreshes roughly every 750 ms.

Displayed state includes:

- approved count;
- target count;
- active count;
- rejected count;
- each slot's latest worker/stage/detail;
- recent events.

The dashboard is a read-only monitor. It does not control the workers.

## Watch mode

Run:

    watch_news_windows.bat

Default behavior:

    run one one-video cycle
    wait 30 minutes
    repeat forever

Custom interval:

    watch_news_windows.bat 10

The argument is minutes.

Current watch file:

    @echo off
    setlocal EnableExtensions
    cd /d "%~dp0"
    set "INTERVAL_MINUTES=30"
    if not "%~1"=="" set "INTERVAL_MINUTES=%~1"
    :loop
    call run_news_video_windows.bat
    ...
    timeout ...
    goto loop

### Watch-mode behavior to understand

The one-video cycle asks for one approved video.

If no verified candidates exist, Main can exit without reaching the requested target.

Watch mode currently does not branch on success/failure before sleeping; it simply runs again after the interval.

Watch mode currently has no built-in phone notification or phone file-transfer step.

## Recommended first live-machine validation sequence

After setup and self-test pass:

### 1. Check local services

Verify:

    ollama list
    ffmpeg -version
    ffprobe -version

If ComfyUI will be used, verify its local web interface/API is reachable.

### 2. Start without ComfyUI

Use:

    run_news_video_windows.bat --category technology --encoder auto

This reduces the first live test to:

- live RSS/article fetch;
- Ollama;
- Kokoro or Qwen fallback;
- FFmpeg.

### 3. Verify the output metadata

Open the latest batch directory and inspect:

- debug.log;
- runtime\worker_001.log;
- slot_...\fact_package.json;
- slot_...\script.json;
- slot_...\audit.json;
- final_videos\*.mp4.json.

Confirm the actual TTS engine is what the logs say.

### 4. Force-test TTS fallback

A controlled failure of Kokoro should result in Qwen3 fallback rather than a misleading "Kokoro" status.

Do not call the fallback verified until a real Qwen WAV is created on the target GPU.

### 5. Test ComfyUI separately

After base video generation works:

    run_news_video_windows.bat --comfyui

Use an explicit imageCheckpoint in defaults.txt.

### 6. Stress-test multiple workers

After single-story operation is stable, run a target such as 5 to 10 videos with multiple workers.

Watch:

- GPU VRAM;
- system RAM;
- ComfyUI stability;
- Qwen service logs;
- FFmpeg failures;
- output duplication;
- worker deadlocks;
- incomplete slots.

## Ollama warm behavior

batch_create_news_videos_windows.bat defaults to keeping Ollama warm unless the user answers N/NO.

The warm flag sets keep_alive=30m.

The unload flag sets keep_alive=0.

Ollama calls are serialized by output\runtime\ollama.lock, so complete video workers may overlap while Ollama generation itself is serialized.

## Qwen helper logs

Persistent helper logs:

    output\runtime\qwen3_tts_server.log
    output\runtime\qwen3_tts_server.error.log

Owner marker:

    output\runtime\qwen3_tts_owner.json

The helper is lazy-started when needed.

AutoNewsRoller only attempts to shut it down when the ownership token proves the process belongs to this AutoNewsRoller instance.

## Failure behavior

### Feed failure

Logged and skipped. Other feeds continue.

### Article extraction failure

Logged. The feed entry can still remain usable with its RSS metadata.

### Verification failure

Candidate is rejected before production.

### Ollama/script failure

Retried up to configured generation retries. If all attempts fail, the slot is rejected.

### Kokoro failure

Qwen3 fallback is attempted.

### Qwen failure

The slot is rejected; a silent narration is not accepted.

### ComfyUI failure

Logged and procedural cards remain in use.

### NVENC unavailable in auto mode

Falls back to x264.

### NVENC explicitly requested but unavailable

Fails instead of silently using CPU.

### Video/audio audit failure

The slot is rejected.

## Windows CI

The repository includes:

    .github\workflows\windows-validation.yml

It runs on windows-latest with:

- Temurin JDK 21;
- Python 3.12;
- batch_create_news_videos_windows.bat --self-test.

It intentionally does not install and execute the heavyweight AI model stack.

## Operational recommendation

For unattended use, Windows Task Scheduler or a startup shortcut can launch watch_news_windows.bat.

However, before configuring unattended boot/start behavior, first complete the live-machine stress test and add a clear notification/error path. An endless loop that fails silently is much less useful than a loop that reports approved output and repeated failures.
