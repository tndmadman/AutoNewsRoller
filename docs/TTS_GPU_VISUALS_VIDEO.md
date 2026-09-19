# TTS, GPU Coordination, Visuals, Captions, and Video Rendering

## Overview

The media side now uses a measured-duration, post-card production chain:

    fact-grounded script
      -> Kokoro narration
      -> Qwen3-TTS only if Kokoro actually fails
      -> ffprobe actual WAV duration
      -> script/TTS revision when outside the 65-72s narration window
      -> 7-9 story-scene plan
      -> persistent ComfyUI SDXL generation
      -> AWARE post/card composition
      -> short ASS phrase captions
      -> animated CFR scene clips
      -> 2-3s source/context tail
      -> FFmpeg final assembly
      -> ffprobe/FFmpeg audit

The most important production rule is that logs and provenance should identify what actually happened, not only what was configured.

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
- af_nicole
- bf_emma

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

ComfyUI remains optional for non-Command-Center/manual workflows.

Command Center production requests ComfyUI by default and requires the configured image profile.

The default profile requests seven unique SDXL illustrations, normally supporting 7-9 story cards. If a normal required-Comfy job cannot produce at least six unique images, the job is rejected instead of silently shipping a mostly procedural video.

## Checkpoint validation

Before submitting generation, AutoNewsRoller queries:

    /object_info/CheckpointLoaderSimple

It collects available checkpoint names.

Behavior:

- if imageCheckpoint is configured and exists, use it;
- if configured but missing, fail the optional image attempt;
- if blank and comfyAutoPickCheckpoint=true, select the first discovered checkpoint;
- otherwise fail the optional image attempt.

A required-Comfy Command Center job treats checkpoint/generation failure as a real production failure. Optional workflows may still retain procedural fallback behavior.

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

Each visual-plan beat now carries a detailed scene-specific prompt describing subject, action, plausible setting, composition, foreground/background, camera framing, realistic lighting, editorial mood, and safe central placement.

Prompts explicitly prohibit readable text, captions, logos, fake interfaces, giant metaphorical objects, and presenting generated imagery as authentic documentary evidence.

The default negative prompt also covers letters/words/subtitles, garbled signage, malformed hands, extra fingers/limbs, duplicated people, distorted faces, bad anatomy, unrelated objects, and low detail.

## Qwen/Comfy handoff

Before ComfyUI generation:

1. acquire exclusive GPU lane;
2. request Qwen /release-gpu;
3. submit ComfyUI work.

This is meant to reduce the chance that a resident Qwen model plus an image checkpoint exhausts VRAM.

After image retrieval, AutoNewsRoller deliberately keeps SDXL/CLIP/VAE resident.

The old normal-success /free call was the direct cause of repeated cold model loads and has been removed.

The /free endpoint remains only in explicit CUDA OOM recovery, where the reason is logged and the failed image is retried once.

## AWARE post/card scenes

Class:

    src/autonewsroller/visuals/CardRenderer.java

Generated SDXL images are no longer used as the entire 1080x1920 frame.

CardRenderer composes:

- AWARE branding;
- concise auto-fit headline;
- centered dominant image with generous margins;
- rounded post/card treatment;
- a dedicated caption area below the image;
- compact source/context footer;
- an AI ILLUSTRATION badge when the main image is generated.

The final source/context card is separate and normally lasts about 2.5 seconds.

## Visual planning

VisualPlanner creates 7-9 POST_CARD story scenes from the final narration plus one short SOURCE_CARD tail.

Typical 68-72 second narration produces eight story scenes.

Scene durations are weighted by the narration carried by each beat rather than rigidly identical.

Each visual-plan item stores:

- narration/body;
- displayCaption;
- positive image prompt;
- negative prompt;
- duration;
- emphasis words;
- transition;
- source metadata.

## Caption generation

CaptionWriter now emits ASS phrase captions.

Default:

    captions=phrase
    captionFontSize=44
    captionMaxWords=9

Normal phrases are 4-9 words and at most two visual lines.

Captions sit in the reserved post-card caption zone below the main image rather than covering faces/subjects.

Restrained emphasis:

- neutral/light text by default;
- cyan for a small number of entities;
- amber for a small number of numbers/results;
- no partisan red/blue ideological shorthand.

Timing is currently proportional to narration words. Kokoro timing.tsv remains available for a future exact-alignment upgrade.

## Video encoder probe

Class:

    src/autonewsroller/video/VideoEncoderProbe.java

Auto mode does not trust the fact that FFmpeg merely lists h264_nvenc.

It performs a real small encode test:

- generated black 256x256 frame;
- h264_nvenc;
- temporary MP4;
- verify nonempty output.

If the test fails:

- auto -> x264 fallback;
- explicit nvenc -> error.

This avoids false positives where FFmpeg was built with NVENC support but the runtime driver/GPU path is broken.

## Final render

VideoRenderer first converts each post-card scene into a deterministic CFR clip with restrained motion such as push-in, pan, or zoom-out.

Final assembly:

- 1080x1920;
- 30 fps;
- -fps_mode cfr;
- yuv420p;
- ASS phrase captions;
- H.264;
- AAC 192k / 48 kHz;
- +faststart.

The story visual scenes span the measured narration duration. A short source/context scene extends the final video by about 2.5 seconds; audio is padded only for that natural ending tail.

NVENC remains preferred after the real encode probe. If final NVENC encoding nevertheless fails, VideoRenderer retries final encoding with x264.

## Video audit

VideoAudit checks:

- MP4 and narration WAV exist and are nontrivial;
- narration is non-silent;
- resolution matches 1080x1920;
- final playable duration is greater than 60 seconds;
- average and nominal frame rates are both approximately 30 fps and match each other;
- final audio/video stream durations are materially aligned.

Audit/provenance also records narration words, measured narration duration, scene counts, unique Comfy image count, actual TTS engine, actual encoder, and measured FPS.

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


## Detailed quality-upgrade reference

See:

    docs/VIDEO_QUALITY_OVERHAUL.md

for the complete duration loop, scene model, post-card design, Comfy residency behavior, and live acceptance procedure.
