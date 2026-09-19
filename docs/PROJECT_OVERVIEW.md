# AutoNewsRoller Project Overview

## Purpose

AutoNewsRoller is a Windows-first, local automated news-to-short-video system.

Its job is to repeatedly discover recent stories, group coverage that appears to describe the same event, require corroboration or a configured authoritative-primary exception, build a fact package, generate an original short script with local Ollama, synthesize narration, build vertical visuals, render and audit a final MP4, and record enough history and provenance to avoid repeatedly producing the same story.

The project is intentionally separate from tndmadman/ThreadGens. ThreadGens was used as an architectural reference for useful production patterns such as serialized local-model access, whole-video parallelism, explicit TTS engine reporting, static monitoring, and GPU-resource coordination. AutoNewsRoller does not modify ThreadGens.

## Current implementation status

The core generator is implemented and merged on main.

Historical implementation merge:

- Repository: tndmadman/AutoNewsRoller
- Implementation pull request: #1, "Implement automated news-to-video pipeline"
- Validated feature head: 326bb53c12a6c2b52caa24f5346b98add17a9420
- Implementation merge commit: 6e3f0e3ac4307b5ec51d79d3d5b78ba97a4d3af7
- ThreadGens main was intentionally left unchanged at the time of implementation.

The Windows fixture validation suite has compiled Java, checked Python helper syntax, parsed the PowerShell monitor, run the Java self-test, and completed an offline fixture dry run.

The remaining major validation gap is live-machine integration testing with the actual local AI stack: real Kokoro synthesis, forced Qwen3-TTS fallback, real Ollama script generation, live ComfyUI generation, NVENC on the target GPU, live RSS/article retrieval, and a sustained multi-worker production run.

## End-to-end pipeline

The intended production flow is:

    configured RSS/Atom feeds
        -> HTTP conditional polling
        -> feed-entry normalization
        -> optional linked-article extraction
        -> age/category filtering
        -> event clustering
        -> independent-source verification
        -> FactPackage construction
        -> ranking
        -> duplicate-story history check
        -> Ollama script generation
        -> script validation
        -> visual planning
        -> procedural cards
        -> optional ComfyUI image replacement
        -> Kokoro narration
        -> Qwen3-TTS fallback if Kokoro fails
        -> FFmpeg vertical render
        -> video/audio audit
        -> final MP4
        -> provenance sidecar
        -> story/publish history

## Design goals

The project was built around the following goals.

### Local-first AI

The script-generation, TTS, optional image generation, and video assembly stack is local-first:

- Ollama for script generation.
- Kokoro as the primary TTS engine.
- Qwen3-TTS 1.7B CustomVoice as the fallback TTS engine.
- ComfyUI as an optional image-generation service.
- FFmpeg/ffprobe for rendering and validation.

The news feeds and linked articles are, by definition, network inputs.

### Factual grounding

Article and feed text is treated as untrusted evidence, not as instructions.

The Ollama prompt says the model may only state facts found in the supplied FactPackage and must not use model memory to fill gaps. It specifically warns against inventing quotes, numbers, dates, casualties, prices, motives, names, locations, forecasts, or political judgments.

The script validator performs additional mechanical checks, including:

- headline present;
- narration present;
- segments present;
- minimum narration length;
- estimated duration bounds;
- numbers in the generated script must already appear in the FactPackage text;
- source labels must correspond to known publishers in the FactPackage.

These checks reduce errors but do not make the system infallible. Human review is still appropriate for consequential or disputed news.

### Corroboration before production

The default configuration requires two independent sources.

A source marked authoritativePrimary can satisfy verification by itself. When that happens, the FactPackage records that it was accepted through the primary-source exception rather than pretending two independent confirmations existed.

Near-identical syndicated content is intended not to count as multiple independent confirmations.

### Self-filling batch production

A batch asks for a target number of approved videos rather than simply launching a fixed number of attempts.

Whole-video workers operate concurrently. A reservation mechanism prevents multiple workers from overshooting a small requested target. Failed attempts are recorded as rejected and do not count toward the approved target.

### Explicit fallback behavior

The system is designed to report which TTS engine actually generated the narration.

It does not merely display a configured preference such as "Kokoro primary / Qwen fallback." The worker state and provenance data identify Kokoro or Qwen3 fallback as the engine actually used.

Optional ComfyUI failures also fall back to procedural cards rather than automatically killing the entire video.

### Traceability

Each produced video has structured artifacts from earlier pipeline stages plus an MP4 sidecar JSON containing provenance such as:

- story ID and fingerprint;
- source list;
- FactPackage hash;
- script hash;
- actual TTS engine and voice;
- image provenance;
- ComfyUI checkpoint when used;
- requested and actual video encoder;
- FFmpeg version and command;
- Ollama model;
- project commit when resolvable;
- final output path.

## Default platform assumptions

The project is Windows-first.

Recommended environment:

- Windows 10 or Windows 11;
- JDK 21 or newer;
- Python 3.12;
- FFmpeg and ffprobe on PATH;
- Ollama for non-dry script generation;
- NVIDIA CUDA-capable GPU for Qwen3-TTS and optional ComfyUI;
- enough VRAM to run the selected local models without uncontrolled overlap.

The implementation was designed with a high-VRAM NVIDIA machine in mind, but actual capacity depends on the models and ComfyUI checkpoint used.

## Main launchers

### build_windows.bat

Compiles every Java source file under src into build\classes.

It does not install packages, download models, run AI inference, or create videos.

### setup_windows.bat

Checks for core prerequisites, creates isolated Python environments, installs Kokoro and Qwen dependencies, verifies CUDA visibility for Qwen, and compiles the Java project.

### run_news_video_windows.bat

Builds Java and asks AutoNewsRoller for one approved video by default. Additional CLI arguments are passed to the Java main class.

### batch_create_news_videos_windows.bat

Interactive production launcher. It asks for:

- approved video target;
- worker count;
- category;
- maximum article age;
- minimum independent sources;
- target duration;
- encoder mode;
- whether ComfyUI should be used;
- whether Ollama should remain warm.

It also opens the PowerShell batch dashboard.

### watch_news_windows.bat

Runs the one-video launcher in an infinite polling loop.

Default interval: 30 minutes.

A different interval can be supplied as the first argument.

Example:

    watch_news_windows.bat 10

This means run a one-video cycle, wait 10 minutes, then repeat.

## What watch mode currently does not do

Current watch mode does not yet include:

- native phone push notifications;
- automatic KDE Connect transfer;
- ntfy integration;
- automatic social-media upload;
- automatic publishing approval workflow;
- per-domain article-page rate limiting beyond feed-level conditional requests and request retry/backoff.

These are documented as proposed integrations, not completed features.

## Documentation map

- ARCHITECTURE_AND_DATA_FLOW.md — internal pipeline and control flow.
- CONFIGURATION_REFERENCE.md — current configuration files and defaults.
- WINDOWS_OPERATIONS.md — setup, build, run, batch, watch, logs, and recovery.
- NEWS_SOURCES_AND_CONTENT_USE.md — source configuration, polling behavior, bot behavior, and content-use cautions.
- TTS_GPU_VISUALS_VIDEO.md — Kokoro, Qwen3-TTS, GPU lane, ComfyUI, captions, FFmpeg, and audit.
- OUTPUT_AUDIT_HISTORY.md — output tree, provenance, history, duplicate controls, and auditing.
- NOTIFICATIONS_AND_PHONE_DELIVERY.md — proposed ntfy/KDE Connect phone delivery architecture.
- VALIDATION_STATUS_AND_ROADMAP.md — what has been tested, what has not, and recommended next work.

## Key implementation principle

The repository should distinguish between three different states:

1. Implemented — code exists in the repository.
2. Fixture/CI validated — code has passed offline automated validation.
3. Live-hardware validated — the actual model/service/GPU path has been exercised on the target machine.

Do not treat those states as interchangeable.
