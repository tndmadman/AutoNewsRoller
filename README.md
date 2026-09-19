# AutoNewsRoller

AutoNewsRoller is a Windows-first, local news-to-video pipeline. It discovers current stories from configurable RSS/Atom feeds, clusters coverage of the same event, requires corroboration, builds a source-backed fact package, asks local Ollama for an original short news script, narrates with Kokoro (Qwen3-TTS fallback), builds vertical visuals, renders H.264/AAC with FFmpeg, audits the result, and records story history so the same event is not repeatedly published.

The project is inspired by useful production lessons from `tndmadman/ThreadGens`, but it is a separate implementation designed specifically for news. It does not modify ThreadGens.


## Full project documentation

The detailed project knowledge is maintained under docs/:

- [Project overview](docs/PROJECT_OVERVIEW.md)
- [Architecture and data flow](docs/ARCHITECTURE_AND_DATA_FLOW.md)
- [Configuration reference](docs/CONFIGURATION_REFERENCE.md)
- [Windows operations](docs/WINDOWS_OPERATIONS.md)
- [Command Center](docs/COMMAND_CENTER.md)
- [News sources, polling, bot behavior, and content use](docs/NEWS_SOURCES_AND_CONTENT_USE.md)
- [TTS, GPU, ComfyUI, captions, and video](docs/TTS_GPU_VISUALS_VIDEO.md)
- [Video quality overhaul and live acceptance](docs/VIDEO_QUALITY_OVERHAUL.md)
- [Output, audit, provenance, and history](docs/OUTPUT_AUDIT_HISTORY.md)
- [Notifications and automatic phone delivery](docs/NOTIFICATIONS_AND_PHONE_DELIVERY.md)
- [Validation status and roadmap](docs/VALIDATION_STATUS_AND_ROADMAP.md)
- [Codebase map](docs/CODEBASE_MAP.md)
- [Development history and design decisions](docs/DEVELOPMENT_HISTORY.md)

The documentation deliberately distinguishes between code that is implemented, behavior validated by fixture/Windows CI, and heavyweight live integrations that still need target-machine validation. Phone notification/KDE Connect delivery is documented as planned work and is not currently implemented.

## Architecture

The Java pipeline under `src/autonewsroller` is split into configuration, ingestion, article extraction, clustering, verification, ranking, script generation, TTS, GPU coordination, visuals, video rendering, auditing, history, and orchestration. Python helpers under `tools/` isolate Kokoro and Qwen3-TTS. `tools/batch_dashboard.ps1` is a static dashboard that reads structured JSONL worker events; it is never rewritten at runtime.

The default flow is:

`RSS/Atom -> normalize -> cluster -> verify -> rank -> FactPackage -> Ollama script -> Kokoro (Qwen fallback) -> measure audio -> revise if needed -> 7-9 scene plan -> persistent ComfyUI -> AWARE post cards -> CFR FFmpeg -> audit -> final video -> history`

Article text is treated as untrusted evidence. The Ollama prompt explicitly limits factual statements to the supplied FactPackage and tells the model not to follow instructions found inside source material. Scripts are original summaries; the system is not intended to reproduce articles verbatim.

## Requirements

Recommended target is Windows 10/11 with JDK 21+, Python 3.12, FFmpeg/ffprobe, Ollama, and an NVIDIA GPU for Qwen3-TTS/ComfyUI. Kokoro is the primary narrator and does not require the GPU lane. Qwen3-TTS is lazy-started only when Kokoro actually fails. Direct non-Comfy workflows can still use procedural cards, while Command Center production requests real ComfyUI imagery by default and rejects a required-Comfy job if the configured image profile cannot be produced.

Default Ollama model: `llama3.1:8b`.

Pull it first if needed:

```bat
ollama pull llama3.1:8b
```

## Windows setup

Run:

```bat
setup_windows.bat
```

The setup script creates isolated `.venv-news`, `.venv-kokoro`, and `.venv-qwen3-tts` environments. It does not alter the system Python installation. Qwen uses a CUDA PyTorch wheel inside its own environment.

Then run the offline validation suite:

```bat
batch_create_news_videos_windows.bat --self-test
```

The self-test compiles Java, checks Python syntax, parses the PowerShell dashboard, validates fixture RSS ingestion, clustering, verification, duplicate behavior, script validation, TTS fallback state, dashboard events, output collision handling, and runs an offline end-to-end dry run.

## Live video-quality acceptance

After setup/self-test, the target Windows GPU machine can run:

```bat
live_acceptance_windows.bat
```

This is intentionally separate from CI because it exercises the real local Ollama, Kokoro, ComfyUI checkpoint, GPU residency, FFmpeg/NVENC path, three real news stories, and representative-frame extraction.

See [Video quality overhaul and live acceptance](docs/VIDEO_QUALITY_OVERHAUL.md).

## Sources

Feeds are configured in `config/sources.json`; source URLs are not hard-coded in Java. Each entry supports `name`, `type`, `category`, `url`, `enabled`, `trustTier`, and `authoritativePrimary`.

Example:

```json
{
  "name": "Example News",
  "type": "rss",
  "category": "technology",
  "url": "https://example.com/feed.xml",
  "enabled": true,
  "trustTier": 2,
  "authoritativePrimary": false
}
```

Categories can include `general`, `world`, `technology`, `science`, `business`, `markets`, `environment`, `health`, `entertainment`, `gaming`, `local`, or custom labels. Add local feeds in configuration rather than changing core code.

By default, substantive breaking-news stories require at least two independent publishers. A configured authoritative primary source can qualify alone, but that status is recorded as a primary-source exception rather than pretending it is independent confirmation. Near-identical syndicated copies are not counted as separate confirmations.

## Batch mode

Interactive mode:

```bat
batch_create_news_videos_windows.bat
```

Direct target/worker example:

```bat
batch_create_news_videos_windows.bat 30 5
```

The launcher asks for category, maximum story age, minimum independent sources, target duration, encoder, optional ComfyUI use, and whether to keep the Ollama model warm. Whole-video workers can overlap. Ollama requests are serialized through a cross-process lock. Qwen uses a shared GPU lane; ComfyUI obtains the exclusive lane and asks the Qwen service to release its resident model before generation.

## Single run and dry run

One normal video:

```bat
run_news_video_windows.bat --category technology --duration 60 --encoder auto
```

Dry run, with no TTS, ComfyUI, or FFmpeg:

```bat
run_news_video_windows.bat --dry-run --batch-target 5 --workers 4
```

Offline fixture dry run:

```bat
java -cp build\classes autonewsroller.Main --dry-run --fixture --batch-target 1 --workers 2
```

## Watch mode and Command Center

`watch_news_windows.bat` now launches the full local Command Center experience:

1. compiles Java;
2. starts a local GPU worker;
3. opens the browser dashboard at `http://127.0.0.1:8787`;
4. runs the controller;
5. scans every enabled RSS feed;
6. keeps verified stories queued for the worker;
7. repeats scans on the configured interval.

The first argument is the RSS scan interval in minutes:

```bat
watch_news_windows.bat 10
```

For a lightweight always-on Linux controller with a separate Windows GPU worker, see [Command Center](docs/COMMAND_CENTER.md).

The original direct one-video and interactive batch launchers remain available for non-controller workflows.

## TTS

Kokoro is always attempted first. A successful narration logs `TTS engine used: Kokoro` and writes narration metadata naming Kokoro and the voice actually used. If Kokoro fails, the failure is logged and Qwen3-TTS is activated as the fallback. A successful fallback logs `TTS engine used: Qwen3 fallback` and writes that actual engine to metadata/provenance.

Qwen uses `Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice` by default and supports the configured voice pool in `defaults.txt`. One voice is kept stable for each video.

## ComfyUI

ComfyUI defaults to `http://127.0.0.1:8188` and is never required. Enable it from the interactive batch launcher or with `--comfyui`.

Set a checkpoint in `defaults.txt`, for example:

```text
imageCheckpoint=RealVisXL_V5.0_fp32.safetensors
```

AutoNewsRoller queries `CheckpointLoaderSimple` choices before submitting work. It will not repeatedly submit a configured checkpoint that does not exist. `comfyAutoPickCheckpoint=false` is the safe default. If generation fails, the root error is logged and procedural cards are used instead.

## Video and captions

Default output is 1080x1920, 30 FPS, H.264 video with AAC audio. `--encoder auto` performs a real short `h264_nvenc` encode test; if that fails, it uses `libx264`. `--encoder nvenc` requires NVENC. `--encoder x264` forces CPU encoding.

Caption modes in `defaults.txt` are `off`, `sentence`, or `word`; the default is `sentence`.

## Output and provenance

Each run creates a timestamped batch directory:

```text
output/batch_YYYYMMDD_HHMMSS/
  debug.log
  runtime/events.jsonl
  runtime/worker_001.log
  slot_001/
    story.json
    articles/
    fact_package.json
    script.json
    visual_plan.json
    narration/
    visuals/
    render/
    audit.json
  final_videos/
    story_name.mp4
    story_name.mp4.json
```

The sidecar JSON keeps source URLs/publishers/timestamps, the fact-package hash, script hash, actual TTS engine and voice, image provenance, Comfy checkpoint when used, requested and actual encoder, FFmpeg command/version, Ollama model, and project commit when it can be resolved.

Persistent history lives in `data/seen_articles.jsonl`, `data/story_history.jsonl`, and `data/publish_history.jsonl`. Final filenames are sanitized and collision-safe.

## Troubleshooting

If no candidate stories are produced, first inspect `output/batch_.../debug.log`: the default acceptance rule needs two genuinely independent sources for the same event, so a category with only one active source may correctly produce zero candidates.

If Kokoro fails, inspect the worker log and `.venv-kokoro`. Qwen should then be attempted automatically. If both fail, that story attempt is rejected rather than producing a silent video.

If ComfyUI fails, AutoNewsRoller should continue using procedural cards. A missing or mismatched checkpoint is reported before prompt submission.

If `auto` encoding uses CPU, run `ffmpeg -encoders | findstr nvenc` and check the batch log. AutoNewsRoller also performs a real encode probe, so merely listing `h264_nvenc` is not considered proof that the encoder actually works.
