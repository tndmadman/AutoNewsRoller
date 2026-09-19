# Video Quality Overhaul

## Purpose

This upgrade moves AutoNewsRoller from short, mostly-static ~20 second outputs to a one-minute vertical social-news format.

Production target:

- narration audio: 65-72 seconds;
- final video: approximately 67.5-74.5 seconds including the short source tail;
- absolute final duration: greater than 60 seconds;
- 1080x1920;
- H.264 + AAC;
- constant-frame-rate 30 fps;
- 7-9 story scenes;
- 6-8 unique ComfyUI images when ComfyUI is required;
- a 2-3 second source/context tail.

## Root causes replaced

The previous implementation had several hard constraints that directly caused the observed output:

- ScriptValidator accepted narration as short as 35 words.
- defaults.txt targeted 60 seconds but did not enforce measured TTS duration.
- VisualPlanner stopped after at most five segment visuals.
- VideoRenderer divided the audio evenly across static images.
- generated images were fed directly as full-screen video frames.
- CaptionWriter used full sentences or individual words rather than short phrases.
- the source card could occupy a large fraction of a short video.
- ComfyImageGenerator called ComfyUI /free with unload_models=true and free_memory=true after every successful image.
- the final renderer relied on concat-image timing plus an fps filter rather than building deterministic CFR scene clips.

## Current production flow

    candidate
      -> production article enrichment
      -> expanded FactPackage
      -> Ollama long-form script
      -> script validation
      -> Kokoro primary TTS
      -> ffprobe real WAV duration
      -> regenerate/resynthesize when outside target
      -> final 65-72s narration audio
      -> 7-9 beat visual plan
      -> persistent ComfyUI session
      -> 6-8 scene-specific SDXL illustrations
      -> AWARE post/card composition
      -> short ASS phrase captions
      -> animated scene clips
      -> short source/context card
      -> CFR 30 fps final assembly
      -> technical audit
      -> provenance

## Narration requirements

The generator asks for:

- a short hook;
- what happened;
- important details;
- supported background/context;
- who or what is affected;
- current status / what happened next when supported;
- a short conclusion.

The normal validated word band is approximately 165-220 words, adjusted for the configured target.

The model is explicitly instructed not to add unsupported information or filler.

If the FactPackage cannot support a substantive one-minute script, generation is rejected rather than padded.

## Actual-audio duration loop

Word count is only a first gate.

After script generation:

1. Kokoro is attempted.
2. Qwen3-TTS is used only if Kokoro actually throws.
3. ffprobe measures the real WAV.
4. If the WAV is below videoTargetAudioMinSeconds or above videoTargetAudioMaxSeconds, Ollama is asked to revise the same fact-grounded script.
5. TTS is synthesized again and measured again.
6. The job is rejected if the configured retry budget cannot reach the required range.

Defaults:

    videoTargetAudioMinSeconds=65
    videoTargetAudioMaxSeconds=72
    narrationDurationRetries=3

No long silence is added to meet the one-minute requirement.

The only normal audio padding is the approximately 2.5 second source-card tail after narration finishes.

## Production article enrichment

Production generation can fetch article pages for cluster members whose body text is missing or short.

Default:

    productionEnrichmentEnabled=true

This happens only when producing a selected story, not across every article in the 88-feed scan.

FactExtractor can use a bounded number of article-body sentences in addition to RSS headline/description facts.

The script still remains constrained to the resulting FactPackage.

## Scene planning

VisualPlanner creates 7-9 POST_CARD story scenes based on measured narration duration.

A typical 68-72 second narration produces eight story scenes.

Scene duration is weighted by the amount of narration in the beat and normally stays around 6-10 seconds.

The final source card is separate and lasts 2-3 seconds.

Each scene records:

- narration/body text;
- short display caption;
- detailed image prompt;
- strong negative prompt;
- duration;
- emphasis words;
- transition;
- source metadata.

## SDXL prompt policy

Positive prompts request:

- beat-specific principal subject;
- relevant action;
- plausible real-world setting;
- foreground/background separation;
- medium-to-wide editorial framing;
- realistic lighting;
- story-appropriate mood;
- professional editorial/news illustration quality;
- central subject placement;
- safe vertical-card composition.

They explicitly reject:

- readable text;
- captions;
- subtitles;
- logos;
- fake interfaces;
- giant symbolic/metaphorical objects;
- presenting an AI illustration as authentic documentary evidence.

Default negative prompt now includes:

    text, letters, words, captions, subtitles, watermark, logo,
    fake UI, garbled signage, malformed hands, extra fingers,
    extra limbs, duplicated people, distorted faces, bad anatomy,
    unrelated objects, low detail, low quality, deformed

## ComfyUI residency

The previous exact reload trigger was the normal-success call:

    POST /free
    {"unload_models":true,"free_memory":true}

after every generated image.

That call has been removed from the normal path.

Current behavior:

1. start or reuse the persistent ComfyUI server;
2. request Qwen GPU release once for the Comfy generation session;
3. submit a stable CheckpointLoaderSimple graph using the same checkpoint;
4. vary prompt/seed/scene inputs;
5. retrieve the output;
6. keep SDXL/CLIP/VAE resident;
7. submit the next scene.

The /free endpoint remains only in explicit CUDA OOM recovery. After an OOM, AutoNewsRoller logs the reason, frees models once, and retries the failed image.

ComfyUI startup flags were not changed by this overhaul. The existing portable launch configuration remains in use.

GpuLane still serializes ComfyUI inference so multiple videos do not submit simultaneous SDXL work to the same protected GPU lane.

## Unique images and reuse

Command Center default:

    commandCenterComfyImages=7

For an eight-scene story, seven unique SDXL renders are produced and one good illustration may be reused for another card/motion treatment.

When ComfyUI is required, production rejects the job if it cannot produce at least six unique images for a normal story.

## AWARE post/card rendering

Generated SDXL files are no longer used as the entire 1080x1920 frame.

CardRenderer builds a social-news post containing:

- AWARE branding;
- concise headline;
- centered dominant image;
- generous side margins;
- rounded card/image treatment;
- reserved caption area below the image;
- source/context footer;
- AI ILLUSTRATION label for generated images.

A darkened version of the illustration can provide subdued background texture behind the foreground card.

## Caption rendering

CaptionWriter now emits ASS rather than sentence-sized SRT captions.

Defaults:

    captions=phrase
    captionFontSize=44
    captionMaxWords=9

Captions are normally:

- 4-9 words;
- no more than two visual lines;
- positioned in the card's caption area below the main image;
- light neutral text;
- cyan emphasis for a small number of entities;
- amber emphasis for a small number of numbers/results.

The caption system does not use partisan red/blue coloring.

Timing is still proportional to narration word count. Exact Kokoro token timing remains a future improvement when reliable timing data is available.

## Motion

Each post-card scene is converted to a CFR scene clip.

Transitions/motion include:

- slow push-in;
- gentle pan left;
- gentle pan right;
- subtle zoom out;
- crossfade-designated/static-safe scenes.

Motion is deliberately restrained.

## Source display

Sources are present in a compact footer throughout story cards.

The final source/context card is polished and intentionally short:

    videoSourceTailSeconds=2.5

No narration paragraph is burned over the source card.

## Final encoding

Each scene is rendered at the final configured resolution and frame rate.

Final assembly explicitly uses:

    1080x1920
    30 fps
    -fps_mode cfr
    H.264
    AAC 192k
    +faststart

NVENC remains preferred when its real encode probe succeeds. If final NVENC encoding still fails, VideoRenderer retries with x264.

The final audio stream is padded only enough to cover the short source-card tail.

## Audit

VideoAudit now rejects:

- missing/empty output;
- silent narration input;
- wrong resolution;
- final duration <=60 seconds;
- average or nominal video rate materially different from 30 fps;
- average/nominal rate mismatch;
- material final audio/video duration mismatch.

The audit JSON also records narration words, narration audio duration, scene counts, unique Comfy image count, and measured average FPS.

## Logging

Production logs now include lines equivalent to:

    [Story] Narration words: 188
    [TTS] Engine actually used: Kokoro voice=af_heart
    [TTS] Audio duration: 69.40 sec
    [Scenes] Planned 8 story scenes + 1 source tail scene
    [Images] Need 7 unique SDXL renders for 8 story scenes
    [ComfyUI] Reusing persistent server
    [ComfyUI] Submitting scene 1/8 unique=1/7
    [ComfyUI] Image 1 completed in 8.42 sec; keeping SDXL/CLIP/VAE resident
    [Render] Building POST_CARD scene 3/9 duration=8.60s transition=zoom_out
    [Render] Final duration: 71.90 sec
    [Render] CFR target: 30 fps; measured average: 30.000 fps

## Live acceptance on the target Windows worker

Run:

    live_acceptance_windows.bat

Optional:

    live_acceptance_windows.bat -Category general -Target 3

The harness performs a real non-dry-run batch with:

- three stories by default;
- one production worker;
- 70 second target;
- ComfyUI enabled;
- the normal Kokoro/Qwen chain;
- the real FFmpeg encoder path.

It validates each completed video for:

- duration;
- resolution;
- average/nominal FPS;
- actual TTS engine;
- narration audio duration;
- narration word count;
- scene count;
- unique Comfy image count.

It also:

- records Comfy image-generation timings;
- checks the new Comfy log region for repeated full SDXL/CLIP/VAE loads;
- requires at least five consecutive image timings;
- extracts four representative JPEG frames from every video;
- writes acceptance_report.json.

Output location:

    output/acceptance_YYYYMMDD_HHMMSS/

Representative frames:

    output/acceptance_.../representative_frames/

## Visual review remains human-required

Automated checks cannot reliably decide whether a generated person's face or hands look natural, whether an image contains subtle gibberish signage, or whether a crop is editorially good.

After the three-video live harness succeeds, inspect the extracted representative frames for:

- headline placement;
- image crop;
- faces/hands;
- accidental generated text;
- caption wrapping and color emphasis;
- margins;
- source footer;
- scene variety.

Do not label the target machine live-validated until this inspection and the five-image warm-reuse check are complete.
