# TTS, GPU Coordination, Visuals, Captions, and Video Rendering

## Overview

The media side of AutoNewsRoller was designed around a predictable fallback chain:

    verified facts
      -> hook candidate generation / validation
      -> locked eight-segment script
      -> Kokoro narration
      -> Qwen3-TTS fallback if needed
      -> procedural visual plan
      -> optional ComfyUI enhancement
      -> FFmpeg render
      -> ffprobe/FFmpeg audit

The most important production rule is that logs and provenance should identify what actually happened, not only what was configured.

## Retention hook system

Production scripts no longer treat all eight narration segments equally.

Before the main script is generated, HookPlanner asks Ollama for three short opening candidates grounded in explicit FACT IDs. Java validates and selects the hook before the rest of the narration is accepted.

The hook contract is:

- 6-11 spoken words, targeting about 8;
- the first 4-6 words must already communicate a concrete verified actor, event, change, conflict, place, or verified number;
- declarative statement, not a rhetorical question;
- no generic throat-clearing such as "Breaking news", "Here's what happened", or "According to reports";
- no unsupported clickbait language such as "shocking", "unbelievable", or "you won't believe";
- numeric claims must already exist in the verified FactPackage;
- model candidates must cite supporting FACT IDs.

Java scores valid candidates for concise length, grounding overlap, multi-source support, early concrete wording, and distance from a simple headline restatement.

If hook generation fails, a deterministic fact-based fallback is used so a hook-model formatting error does not kill an otherwise valid video.

The selected hook is locked into narration segment 0. The main script model is told to copy it exactly, but Java overwrites segment 0 with the selected hook regardless, so prompt noncompliance cannot silently expand or rewrite it.

For a roughly 175-word / 70-second script, the intended shape is approximately:

    segment 0: 6-11 words // retention hook
    segments 1-7: roughly 23-24 words each

Segment 1 is explicitly the immediate payoff. It should explain the hook instead of restarting the story with another introduction.

Normal short/long narration repair intentionally excludes segment 0. Unsupported-number repair also operates on body segments because HookPlanner already validates hook numbers against the FactPackage.

Hook diagnostics are written into script.json, audit.json, and the final MP4 sidecar, including selected text, hook type, cited fact IDs, word count, estimated spoken duration, fallback state, score, and candidate diagnostics.

## Kokoro primary TTS

Java class:

    src/autonewsroller/tts/KokoroNarrator.java

Python helper:

    tools/kokoro_tts.py

KokoroNarrator:

1. writes narration text to a temporary text file next to the target WAV;
2. selects .venv-kokoro\Scripts\python.exe when present;
3. calls tools/kokoro_tts.py;
4. waits up to 300 seconds;
5. verifies a nonempty WAV exists;
6. writes narration metadata JSON;
7. reports engine=Kokoro and the actual voice.

Kokoro helper defaults:

- model/repo: hexgrad/Kokoro-82M
- sample rate: 24000 Hz
- default voice if invoked directly: af_heart
- default language code: a
- default speed: 1.0

The configured Java voice pool is:

- af_heart
- af_bella
- bf_emma

`af_nicole` is intentionally blacklisted. AutoNewsRoller removes both `af_nicole` and `af-nicole` from configured Kokoro voice pools at runtime, and the Python Kokoro helper refuses direct use of that voice. If an old/custom configuration contains only blacklisted entries, the runtime falls back to the safe pool above.

## Kokoro token timing

tools/kokoro_tts.py checks whether Kokoro result tokens expose start_ts and end_ts.

If timestamps are available, it writes:

    narration.timing.tsv

The file format begins:

    autonewsroller-kokoro-timing-v1

and stores word text encoded with URL-safe Base64 plus start/end timestamps.

Current limitation:

VideoRenderer currently generates subtitles using CaptionWriter's duration-proportional timing. It does not yet use the Kokoro token-timing file for exact captions.

A future caption improvement should prefer exact Kokoro timings when available.

## Qwen3-TTS fallback

Java class:

    src/autonewsroller/tts/QwenNarrator.java

Python client/bootstrap:

    tools/qwen3_tts.py

Persistent service:

    tools/qwen3_tts_server.py

Default model:

    Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice

Default Qwen voice pool:

- Ryan
- Aiden
- Ono_Anna
- Sohee

The service uses CUDA and refuses to load the model if torch.cuda.is_available() is false.

Model dtype:

- bfloat16 when supported;
- otherwise float16.

Default attention implementation:

    sdpa

It can be overridden with AUTONEWS_QWEN3_ATTN.

## Lazy persistent Qwen service

Qwen is not intended to reload the 1.7B model for every narration.

tools/qwen3_tts.py:

1. calls /health on localhost;
2. if unavailable, launches qwen3_tts_server.py;
3. waits for health readiness;
4. POSTs narration to /synthesize;
5. writes returned WAV.

The server only loads the model when synthesis is first requested.

This avoids paying model-load cost when Kokoro works normally.

## Qwen service endpoints

### GET /health

Returns information including:

- ok;
- model ID;
- whether model is loaded;
- whether GPU release is supported;
- ownership token when this process was started by AutoNewsRoller.

### POST /synthesize

Accepts text, speaker, language, instruction, and worker ID.

Returns audio/wav.

### POST /release-gpu

Drops the resident Qwen model, runs garbage collection, and calls torch.cuda.empty_cache().

### POST /shutdown

Requires the correct ownership token.

## Process ownership protection

When AutoNewsRoller starts Qwen, it generates a UUID owner token and records:

- PID;
- token;
- port

in:

    output/runtime/qwen3_tts_owner.json

OwnedProcesses.shutdownQwen first verifies that the live service reports the same token.

It will not intentionally stop a service it cannot prove it owns.

## Actual-engine reporting

NewsPipeline emits explicit states:

Kokoro attempt:

    KOKORO active

Kokoro success:

    KOKORO USED

If Kokoro throws:

    Kokoro failed: <root error>

Then Qwen:

    QWEN3 FALLBACK active

Qwen success:

    QWEN3 FALLBACK USED

Worker logs also say:

    TTS engine used: Kokoro

or:

    TTS engine used: Qwen3 fallback

The final provenance sidecar writes ttsEngineActuallyUsed and voice.

This was deliberately added to avoid ambiguous status such as merely showing "Kokoro primary, Qwen fallback" without knowing which engine generated the WAV.

## GPU lane

Class:

    src/autonewsroller/gpu/GpuLane.java

Lock file:

    output/runtime/gpu_ai_lane.lock

Two modes:

- shared
- exclusive

Qwen narration acquires a shared GPU lane.

ComfyUI work acquires an exclusive GPU lane.

The design allows compatible shared GPU work while preventing ComfyUI from overlapping the protected heavy path.

The actual behavior also depends on Windows/JVM file-lock semantics and external processes that do not participate in the lock.

ComfyUI itself does not know about the Java file lock. AutoNewsRoller controls when it submits work.

## ComfyUI optional visuals

Class:

    src/autonewsroller/visuals/ComfyImageGenerator.java

Default endpoint:

    http://127.0.0.1:8188

ComfyUI is optional.

The pipeline always has procedural cards available.

When enabled, it replaces eligible non-source story visuals up to the configured image limit. The opening HOOK visual is first in the eligible order, so it is generated before later story beats.

## Checkpoint validation

Before submitting generation, AutoNewsRoller queries:

    /object_info/CheckpointLoaderSimple

It collects available checkpoint names.

Behavior:

- if imageCheckpoint is configured and exists, use it;
- if configured but missing, fail the optional image attempt;
- if blank and comfyAutoPickCheckpoint=true, select the first discovered checkpoint;
- otherwise fail the optional image attempt.

The caller catches the failure and keeps procedural cards.

This prevents repeated blind submission of a nonexistent checkpoint.

## ComfyUI workflow

The programmatically assembled graph contains:

- CheckpointLoaderSimple
- positive CLIPTextEncode
- negative CLIPTextEncode
- EmptyLatentImage
- KSampler
- VAEDecode
- SaveImage

KSampler defaults in code:

- random-ish positive seed based on prompt/time;
- configured step count;
- configured CFG;
- sampler_name=dpmpp_2m_sde;
- scheduler=karras;
- denoise=1.0.

The generated prompt adds:

    editorial news illustration, no text, no logos, vertical composition

Default negative prompt is configurable and includes text, watermark, logo, captions, low quality, distorted, and deformed.

## Qwen/Comfy handoff

Before ComfyUI generation:

1. acquire exclusive GPU lane;
2. request Qwen /release-gpu;
3. submit ComfyUI work.

This is meant to reduce the chance that a resident Qwen model plus an image checkpoint exhausts VRAM.

After image retrieval, AutoNewsRoller attempts a ComfyUI /free request with unload_models and free_memory.

## Procedural cards

Class:

    src/autonewsroller/visuals/CardRenderer.java

Procedural cards use Java2D.

Default final card canvas uses the final video width/height.

Cards contain:

- dark background;
- rounded content panel;
- wrapped title;
- wrapped body;
- AutoNewsRoller plus visual type footer.

The procedural path is important because it provides a deterministic fallback when ComfyUI is unavailable or inappropriate.

## Visual planning

VisualPlanner now starts directly on the story instead of spending the opening seconds on a standalone headline card.

It creates:

1. segment 0 as a HOOK visual at frame zero;
2. the remaining segment-driven visual items;
3. a short source card at the end.

The old 3.5-second opening HEADLINE_CARD is intentionally removed from production plans. The hook scene receives a short duration weight tied to its narration word count, while later scenes receive proportionally larger weights.

The hook visual prompt is grounded in the FACT IDs selected by HookPlanner and asks for a concrete editorial/documentary subject or action rather than a generic newsroom image. CardRenderer also gives HOOK images a larger image-first layout.

The source card lists distinct publisher names.

## Caption generation

CaptionWriter supports:

- off
- sentence
- word

For sentence mode, narration is split into sentences.

For word mode, narration is split into words.

Duration is distributed proportionally to each unit's word count.

This is an approximation.

Potential future order of preference:

1. exact TTS word timestamps;
2. forced-alignment timestamps;
3. proportional fallback.

## Video encoder probe

Class:

    src/autonewsroller/video/VideoEncoderProbe.java

Auto mode does not trust the fact that FFmpeg merely lists h264_nvenc.

It performs a real small encode test:

- generated black 64x64 frame;
- h264_nvenc;
- temporary MP4;
- verify nonempty output.

If the test fails:

- auto -> x264 fallback;
- explicit nvenc -> error.

This avoids false positives where FFmpeg was built with NVENC support but the runtime driver/GPU path is broken.

## Final render

VideoRenderer:

- probes WAV duration;
- splits duration across visuals;
- creates concat input;
- generates SRT;
- scales/pads to configured resolution;
- outputs 30 FPS by default;
- uses yuv420p;
- burns subtitles when enabled;
- uses -shortest;
- AAC audio at 192k;
- +faststart.

NVENC defaults:

- h264_nvenc
- preset p6
- tune hq
- vbr
- cq 19
- b:v 0

x264 defaults:

- libx264
- preset medium
- crf 19

## Video audit

VideoAudit checks:

- MP4 exists;
- MP4 size is at least nontrivial;
- WAV exists;
- WAV size is nontrivial;
- volumedetect does not report mean_volume: -inf;
- first video stream resolution matches configured width x height;
- duration is positive.

Only approved outputs are copied to final_videos.

## What still needs live validation

The code path exists, but the following should not be described as target-machine verified until actually exercised there:

- Kokoro producing real narration;
- Kokoro failure causing real Qwen fallback;
- Qwen 1.7B model loading on the target GPU;
- Qwen releasing VRAM before ComfyUI;
- ComfyUI loading the selected real checkpoint;
- multi-worker VRAM behavior;
- NVENC on the target FFmpeg/driver;
- sustained 5-10 video production without model/service crashes.

## Recommended live stress-test observations

Record:

- peak dedicated GPU memory;
- peak shared GPU memory;
- system RAM;
- Qwen model load time;
- Qwen release time;
- Comfy checkpoint load time;
- average narration generation time;
- average render time;
- failure/retry count;
- whether ComfyUI becomes unstable after repeated runs;
- whether Qwen can reload after ComfyUI releases;
- whether final TTS metadata always matches the actual WAV path.


## Command Center media reporting

Command Center jobs request ComfyUI by default.

If ComfyUI succeeds, NewsPipeline emits a live `COMFYUI USED` event containing the checkpoint and generated filename. If it cannot be used, the live event explicitly reports `COMFYUI FALLBACK` or `COMFYUI SKIPPED` and procedural cards remain in the render.

The TTS stage likewise emits the actual path:

- `KOKORO USED voice=...`
- or `KOKORO FAILED: ...` followed by `QWEN3 FALLBACK USED voice=...`

The final MP4 sidecar remains authoritative and the Command Center copies these values into the persistent story/video state so the website displays the engine, voice, visual mode, checkpoint, and ComfyUI image count after completion.
