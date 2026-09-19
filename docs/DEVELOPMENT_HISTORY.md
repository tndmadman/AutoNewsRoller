# Development History and Design Decisions

## Origin

AutoNewsRoller was created as a separate project after work on tndmadman/ThreadGens demonstrated a useful local automated-video architecture.

The new requirement was not "change ThreadGens to scrape news."

The requirement was to make a distinct repository that could:

- automatically discover current news;
- verify stories before production;
- create source-grounded scripts;
- use local TTS;
- optionally use local image generation;
- render finished vertical videos;
- run several whole-video workers;
- keep producing until a requested approved-video target was reached;
- retain audit/provenance/history.

The target repository selected was:

    tndmadman/AutoNewsRoller

ThreadGens was intentionally left unchanged.

## ThreadGens lessons carried over

The implementation reused architectural lessons rather than copying the application wholesale.

Important lessons:

### Serialize Ollama

Multiple full video workers can exist, but local LLM generation should not all hit Ollama at once.

AutoNewsRoller uses a file lock around Ollama generation.

### Whole-video workers

Parallelism is organized around complete video jobs rather than trying to make every small stage independently concurrent.

This keeps slot ownership and failure handling understandable.

### Report the actual TTS engine

A prior practical problem was ambiguity between configured TTS preference and the engine that actually produced audio.

AutoNewsRoller logs and writes provenance for the actual engine.

### Keep Qwen persistent/lazy

A large fallback TTS model should not load/unload for every narration if avoidable.

The Qwen helper is a lazy persistent service.

### Coordinate heavy GPU consumers

Qwen and ComfyUI can compete for VRAM.

AutoNewsRoller uses a shared/exclusive GPU lane and asks Qwen to release its model before Comfy generation.

### Static monitor

The dashboard reads structured event data.

The program does not continuously rewrite dashboard script source to show status.

## Initial repository state

AutoNewsRoller initially existed as an empty GitHub repository.

It was bootstrapped and then populated with the new project.

A small README commit was used to establish repository state before the full implementation branch was built.

## Implementation branch

Main implementation work used:

    feature/news-video-pipeline

The implementation was built/tested locally and transferred into the GitHub repository.

## Temporary bootstrap mechanism

Because the initial repository was empty and connector/write constraints made a full large-tree push awkward, the tested local project was temporarily packaged for transfer.

A compressed archive was split into temporary bootstrap chunks.

A one-shot GitHub Actions workflow reassembled and extracted the verified archive.

This was a transfer mechanism only, not part of the final product architecture.

## Bootstrap failure and cleanup

The first bootstrap attempt hit a GitHub Actions token permission limitation when it attempted to create a workflow file.

The project transfer was adjusted so the permanent workflow was added separately.

Temporary bootstrap artifacts/workflow were removed after the source tree was established.

A final repository-tree audit confirmed no .bootstrap/bootstrap-project artifacts remained.

This history matters only for understanding how the initial large implementation entered the empty repository. It is not required to build or run AutoNewsRoller.

## Windows build bug discovered during validation

The first Windows validation pass exposed an important issue.

The apparent workflow result was not sufficient evidence that Java compilation had actually succeeded.

Actual job logs showed javac failure related to the response/source-list path.

There was also fragile cmd error propagation.

Fix:

- enumerate Java files using PowerShell;
- write quoted full paths;
- normalize backslashes to forward slashes;
- explicitly test errorlevel after each important step.

Validation was rerun.

The resulting logs explicitly contained:

    Java compile passed.

## Self-test growth

The final implementation validation included 15 Java self-test checks covering:

- JSON;
- source config;
- RSS parsing;
- clustering;
- verification;
- syndication behavior;
- authoritative-source exception;
- malformed feed handling;
- deterministic dry-run script;
- script validation;
- TTS fallback logic;
- dashboard events;
- output filename collisions;
- NVENC mode logic.

## Target overshoot bug discovered during fixture testing

An early multi-worker dry run requested:

    target=1
    workers=2

and produced two approvals.

Cause:

Two workers could both begin a story while approved count was still zero.

Fix:

BatchCoordinator added an in-flight reservation count guarded by a reservation lock.

Workers only reserve work if:

    approved + inFlight < target

After the fix, the same test produced exactly:

    Approved 1 / target 1 after 1 attempt(s)

This is an important concurrency regression test.

## FFmpeg smoke validation

A local FFmpeg x264 smoke render was performed during implementation.

The output was:

- 1080x1920;
- H.264;
- non-silent synthetic audio;
- positive duration.

The VideoAudit path accepted it.

This validates the render/audit mechanics without claiming that the target machine's NVENC setup has been proven.

## Pull request and merge

Implementation PR:

    #1 Implement automated news-to-video pipeline

Validated feature head:

    326bb53c12a6c2b52caa24f5346b98add17a9420

Merge commit:

    6e3f0e3ac4307b5ec51d79d3d5b78ba97a4d3af7

Historical feature Windows validation run:

    35461309135

Historical post-merge main validation run:

    35461369719

Both completed successfully after the Windows build corrections.

## What CI intentionally did not do

CI was deliberately kept lightweight enough to be practical.

It did not:

- download giant local AI models;
- require an NVIDIA GPU;
- load Qwen3-TTS;
- synthesize real Kokoro narration;
- call live ComfyUI;
- run live Ollama generation;
- crawl current internet feeds as a required test.

Instead, deterministic fixtures validate the core orchestration.

This is why live-machine validation remains a separate project phase.

## Source architecture decision

The initial source list is intentionally config-driven rather than embedded in code.

The source pack began small:

- BBC World;
- Guardian World;
- Guardian Technology;
- Ars Technica;
- NASA JPL News.

The intent was to prove source ingestion, clustering, verification, and primary-source exception behavior before building a large feed catalog.

The source pack should be expanded only after observing live clustering quality and reviewing intended usage terms.

## RSS-first decision

RSS/Atom was chosen as the initial discovery system because it is:

- machine-readable;
- intended for periodic automated retrieval;
- relatively stable;
- easy to cache conditionally;
- less expensive than crawling publisher home pages;
- useful for source attribution.

Linked article HTML is fetched only when the feed description is short.

That is a separate crawling concern and should eventually receive stronger per-domain throttling/caching.

## Original-script decision

The pipeline is designed to generate a new summary from a FactPackage, not rewrite one article paragraph-by-paragraph.

Reasons:

- factual corroboration;
- lower dependence on any single publisher's wording;
- easier provenance;
- better resistance to prompt injection in source text;
- more appropriate architecture for multi-source news.

## Procedural-card fallback decision

The project does not require ComfyUI to produce a video.

Reasons:

- image generation can fail independently;
- checkpoint names vary;
- VRAM pressure can be severe;
- synthetic imagery can be inappropriate for some news;
- a reliable text/card fallback is essential for unattended generation.

## NVENC probe decision

The project performs an actual short h264_nvenc encode instead of only checking encoder listings.

Reason:

FFmpeg can list h264_nvenc even when the installed driver/GPU/runtime cannot successfully encode.

## Ownership-token decision

AutoNewsRoller does not blindly kill a process on port 8765 during shutdown.

The Qwen helper records a random token, and Java checks that the live process reports the same token.

Reason:

A localhost port is not proof that the service belongs to this process.

## Current project phase

The repository has moved from architecture/implementation into integration hardening.

Primary remaining questions are operational:

- how the real source pack behaves;
- how the actual local models behave;
- whether the GPU can sustain Qwen + Comfy workflows;
- whether multi-worker generation remains stable over hours;
- whether the source pack and content-use rules fit the intended publishing model;
- how finished MP4s should be delivered/published automatically.

## Planned phone-delivery direction

A later discussion established a likely design:

- KDE Connect for transferring the actual MP4 to Android;
- ntfy for structured push notifications.

That integration is documented but not yet implemented.

It should trigger from the known final approved video path inside NewsPipeline, not from a fragile folder-counting batch hack.

## Documentation phase

The docs/full-project-documentation branch was created from implementation main commit:

    6e3f0e3ac4307b5ec51d79d3d5b78ba97a4d3af7

Its purpose is to preserve the project's current architecture, operational knowledge, known limitations, and roadmap inside the repository instead of leaving that information only in chat history.
