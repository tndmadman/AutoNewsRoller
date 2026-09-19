# Codebase Map

## Root files

### README.md

Top-level introduction, setup summary, and entry points into the detailed documentation.

### LICENSE

Repository license.

### defaults.txt

Primary simple key/value runtime defaults.

### requirements-kokoro.txt

Python dependencies for the isolated Kokoro environment.

### requirements-qwen3-tts.txt

Python dependencies for the isolated Qwen3-TTS environment in addition to CUDA PyTorch and qwen-tts installed by setup_windows.bat.

### setup_windows.bat

Windows dependency/environment setup.

### build_windows.bat

Java source enumeration and javac build.

### run_news_video_windows.bat

Simple one-video launcher.

### batch_create_news_videos_windows.bat

Interactive batch launcher and self-test entry point.

### watch_news_windows.bat

Infinite polling loop around the one-video launcher.

## .github/workflows

### windows-validation.yml

Windows CI using JDK 21 and Python 3.12.

Runs:

    batch_create_news_videos_windows.bat --self-test

## config

### config/sources.json

Configured RSS news sources and source metadata.

### config/categories.json

Known/default category labels.

### config/ranking.json

Story-ranking weights.

## data

### data/.gitkeep

Keeps the runtime-data directory in git.

Runtime-created files include:

- feed_cache.json
- seen_articles.jsonl
- story_history.jsonl
- publish_history.jsonl

## src/autonewsroller

## Main.java

Application entry point.

Responsibilities:

- CLI parsing;
- config load;
- shutdown hook;
- batch directory selection;
- pipeline construction;
- coordinator invocation;
- final exit status.

## SelfTest.java

Offline deterministic Java self-test.

## audit package

### NewsAudit.java

Audit-related model/support for news pipeline validation.

### SourceAudit.java

Source-level audit support.

### VideoAudit.java

Final technical MP4/WAV audit using FFmpeg/ffprobe.

## cluster package

### SimilarityScorer.java

Computes pairwise article similarity.

### StoryClusterer.java

Builds same-event clusters and semantic fingerprints.

Default cluster threshold: 0.52.

## config package

### NewsConfig.java

Loads defaults.txt and ranking.json.

Provides typed accessors for common settings.

### SourceConfig.java

Represents one configured source.

## gpu package

### GpuLane.java

File-lock-based shared/exclusive GPU coordination.

Lock file used by pipeline:

    output/runtime/gpu_ai_lane.lock

## history package

### ArticleHistory.java

Append-only seen-article records.

### StoryHistory.java

Semantic generated-story history and fingerprint lookup.

### PublishHistory.java

Append-only approved-output history.

## ingest package

### NewsSource.java

Source abstraction.

### FeedRegistry.java

Loads config/sources.json.

### FeedCache.java

Persists HTTP ETag/Last-Modified cache metadata.

### RssSource.java

RSS/Atom HTTP polling and XML parsing.

### ArticleFetcher.java

Fetches linked HTML pages for feed entries needing enrichment.

### ArticleParser.java

Dependency-free HTML-to-text extraction.

## model package

### Article.java

Normalized source article.

### StoryCluster.java

Same-event article group, entities, and fingerprint.

### FactClaim.java

One extracted claim plus supporting URLs/confidence/contested flag.

### FactPackage.java

Grounding package for script generation.

### NewsScript.java

Generated headline/narration/segments/source labels.

### VisualPlan.java

Planned visual sequence.

## orchestration package

### NewsPipeline.java

Main per-story pipeline.

Owns:

- discovery;
- verification;
- ranking integration;
- slot artifacts;
- script production;
- visuals;
- TTS;
- rendering;
- audit;
- final-copy/provenance/history.

### BatchCoordinator.java

Whole-video parallelism, candidate assignment, target reservation, approval/rejection counting.

### EventLog.java

Append-only structured events.jsonl writer.

### RuntimeLog.java

debug.log and per-worker log writer.

### PipelineStage.java

Structured stage enum.

### WorkerState.java

Serializable dashboard event/state.

### OwnedProcesses.java

Safe Qwen helper cleanup based on ownership token verification.

## rank package

### StoryRanker.java

Weighted candidate scoring.

## script package

### OllamaClient.java

Serialized local Ollama JSON generation client.

Uses:

    output/runtime/ollama.lock

### NewsScriptGenerator.java

Grounded prompt, retries, JSON parse, deterministic dry-run script.

### ScriptValidator.java

Mechanical checks for script structure, duration, supported numbers, and valid source labels.

## tts package

### NarrationEngine.java

TTS interface.

### NarrationResult.java

Actual narration result and metadata.

### KokoroNarrator.java

Java launcher for tools/kokoro_tts.py.

### QwenNarrator.java

Java launcher for tools/qwen3_tts.py under a shared GPU lane.

### FallbackNarrator.java

Generic primary/fallback helper used in tests.

### ProcessRunner.java

Subprocess execution support.

## util package

### Json.java

Internal JSON parser/serializer and file helpers.

### Text.java

Text normalization, token/sentence/entity helpers.

### Hashing.java

SHA-256 helpers.

### FileNames.java

Safe/collision-resistant output names.

## verify package

### FactExtractor.java

Extracts candidate factual claims from cluster headlines/descriptions.

### SourceVerifier.java

Independent-source deduplication, authoritative-primary exception, FactPackage assembly.

### VerificationResult.java

Verification result wrapper.

## video package

### CaptionWriter.java

Sentence/word SRT timing generation.

### FfmpegRunner.java

FFmpeg/ffprobe process execution.

### VideoEncoderProbe.java

Encoder normalization and real h264_nvenc smoke probe.

### VideoRenderer.java

Concat visual timing, subtitles, scale/pad, H.264/AAC final render.

## visuals package

### VisualPlanner.java

Headline/content/source-card sequence generation.

### CardRenderer.java

Java2D procedural vertical cards.

### ComfyImageGenerator.java

Optional local ComfyUI workflow, checkpoint validation, output retrieval, GPU release coordination.

## tools

### tools/kokoro_tts.py

Kokoro helper.

Outputs WAV and optional token timing sidecar.

### tools/qwen3_tts.py

Qwen client/bootstrap.

Starts persistent server if not already healthy.

### tools/qwen3_tts_server.py

Lazy CUDA Qwen3-TTS server.

Endpoints:

- /health
- /synthesize
- /release-gpu
- /shutdown

### tools/batch_dashboard.ps1

Static polling dashboard for runtime/events.jsonl.

### tools/fetch_news.py

Python-side helper retained for news-fetch tooling/support.

### tools/article_extract.py

Python-side article extraction tooling/support.

The primary current Java discovery path uses RssSource, ArticleFetcher, and ArticleParser.

## tests/fixtures

### source_a.xml
### source_b.xml
### source_c.xml

Controlled overlapping/unrelated stories for cluster and verification tests.

### authoritative.xml

Primary-source exception fixture.

### malformed.xml

Malformed-feed failure fixture.

## docs

### PROJECT_OVERVIEW.md

System purpose, status, design goals, launchers, and documentation index.

### ARCHITECTURE_AND_DATA_FLOW.md

Detailed runtime flow.

### CONFIGURATION_REFERENCE.md

Current defaults and source/ranking config behavior.

### WINDOWS_OPERATIONS.md

Windows setup, build, execution, self-test, watch, and failure behavior.

### NEWS_SOURCES_AND_CONTENT_USE.md

RSS/article acquisition behavior, bot identity, polite crawling, and source/content-use cautions.

### TTS_GPU_VISUALS_VIDEO.md

Kokoro/Qwen, GPU lane, ComfyUI, captions, rendering, and media audit.

### OUTPUT_AUDIT_HISTORY.md

Intermediate artifacts, sidecar provenance, persistent history, and duplicate controls.

### NOTIFICATIONS_AND_PHONE_DELIVERY.md

Planned phone push/file-transfer design.

### VALIDATION_STATUS_AND_ROADMAP.md

Verified behavior, live validation gaps, and prioritized next work.

### DEVELOPMENT_HISTORY.md

Why the project was created and notable implementation/validation events.
