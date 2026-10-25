# Source-grounded meme / ragebait video pipeline

## Operator summary

`MEME_RAGEBAIT` is an **independent third video-production mode**, not a variation of the
standard-then-framing paired flow. Select an RSS story in the Command Center and press
**MAKE MEME / RAGEBAIT**. Completed videos are titled **Meme Roast: [story title]** in the
board and retained in the normal version archive.

Requires the regular controller, updated Windows GPU worker, local Ollama, Kokoro (with Qwen
fallback), local ComfyUI and FFmpeg/ffprobe. Updated workers advertise
`meme_ragebait_v1`; old workers cannot claim the new jobs.

## Architecture

1. Existing RSS scanning, article enrichment and SourceVerifier gather attributable text.
2. `MemeEvidenceBuilder` bounds article excerpts and facts, preserving source URLs,
   uncertainty and a source fingerprint. Content from web publishers is **untrusted data**.
3. `MemeScriptGenerator` asks local Ollama for specific comedy mechanisms:
   expectation reversal, absurdity, ironic escalation, literal analogies and callbacks.
   `MemeHookPlanner` proposes six hooks and rejects generic or source-unanchored hooks.
   Once selected, **Java owns the hook**: its caption, spoken line, source fact IDs and
   opening segment are constructed before the continuation is assembled. The final first
   segment is always `HOOK`, at index 0; Ollama never needs to repeat the locked words.
   The continuation prompt asks for only SETUP, CONTEXT, JOKE and PAYOFF beats.
   A top-level legacy `hookText`/`spokenHook` is ignored. One clearly duplicated
   opening beat is stripped; a contradictory/ambiguous HOOK beat is rejected for revision.
4. `MemeScriptValidator` rejects repeated TTS lines, cliches, overlong narration
   beats, missing early jokes, unsupported numbers and direct quotations.
   A separate `MemeComedyReviewer` uses Ollama to rate the exact spoken lines,
   excluding images and music, for instant hook impact, specificity, joke density,
   final payoff, spoken flow, and factual integrity. Weak ratings cause a full script
   revision and, if the opening is weak, a new hook concept before any TTS or images.
   A passing review is recorded with the script metadata. Human editorial judgment
   is still necessary; Ollama can misjudge humor.
5. `MemeVisualPlanner` maps beat captions and descriptions to scene timing.
   `MemeCardRenderer` produces 9:16 cartoon-oriented cards, Java-rendered captions and
   a distinct **SATIRE / AI ILLUSTRATION** disclosure. Captions are not delegated to ComfyUI.
6. The existing image generator, image inspector, narration engine, visual director, audio
   alignment, FFmpeg renderer, audit and sidecar are reused. ComfyUI prompts use an
   illustration strategy instead of realistic wire photography.
7. `ComedyIntroAudioMixer` synthesizes an original three-hit comedy rimshot. It does
   **not** include copyrighted stand-up, television or commercial audio. The old news
   stings are unchanged.

## Duration and opening

Meme scripts have flexible beat counts. The 30-second target is only a writing hint; a
good 10-second script is valid. The meme path bypasses the 61-second standard minimum,
the worker's 70-second requested target and the framing repair floor. Actual audio WAV
duration controls the final FFmpeg cut. The first segment is the very first frame: no
silent title slate or breaking-news introduction.

A 220px scaled **text-free** dark footer is preserved for platform post descriptions.
The satire disclosure appears immediately above it, and the meme layout rotates
multiple caption/reaction treatments without touching the standard-news header,
ticker or TikTok-safe layout.

## Queue and archive

The controller owns one active video slot per RSS story. When users request a meme while
another mode is queued or producing, the request is stored as `pendingMemeRequest` in
controller state, surfaced on an independently identified
`<storyId>::MEME_RAGEBAIT` waiting card and promoted once the occupied slot becomes
available. A successful normal video still queues its paired framing review first;
the meme waits until that review finishes. Restart and rescan preserve pending requests.
The `MAKE_MEME` and `REMAKE_MEME` actions use existing operation IDs, claim tokens and
lease recovery. Each successfully completed meme is archived as a version with its own
posting status, upload/scrap flags, Comfy image count and openable MP4.

## Configuration

```properties
memePipelineEnabled=true
commandCenterAutoMeme=false
memeDurationHintSeconds=30
memeComedyReviewEnabled=true
memeComedyMinimumScore=7
memeUseComfy=true
memeRequireComfy=true
memeComfyImages=3
memeIntroSoundEnabled=true
memeIntroSoundGainDb=-10
```

ComfyUI defaults to required; when it is unavailable or cannot produce acceptable images,
the job fails rather than silently claiming it generated AI illustrations. The meme
pipeline uses the same full-step SDXL/RealVisXL baseline (832×1472, 32 steps, CFG 5.5,
DPM++ SDE/Karras) as standard news, with a **separate satire illustration prompt
profile and negative prompt**. Captions are painted by Java rather than requested
as lettering from the image model.

Set `memeImageNegative` in `defaults.txt` to tune the comic style exclusions.
Set `memeImageCheckpoint` to the filename of a compatible **non-Lightning SDXL
illustration checkpoint** when one is installed in ComfyUI. If left blank, the
meme pipeline inherits `imageCheckpoint` (the default RealVisXL realism model,
which can be less effective for cartoons). The chosen model must be discoverable
from ComfyUI; a bad explicit name fails clearly rather than switching silently.
Sampler, dimensions, steps, CFG, seeds and generation attempts remain logged per
image in final sidecar provenance. FRAMING_EXPOSURE is deliberately excluded from
AI illustration generation and keeps its deterministic evidence cards.

## Failure handling and provenance

Story and final-video sidecars preserve mode, source URLs, fingerprints, script and angle,
hook candidate scores and rejection diagnostics, scene timing, actual voice, encoder,
Comfy checkpoint/seeds/image paths, the original synthesized sound identity and gain, and
auditing results. The controller counts both historic `comfyui-generated` and current
`comfyui-generated-ai-illustration` image provenance types.

Script-stage errors are repaired within the bounded `ollamaRetries` budget.
Presentation/legacy opening normalization happens in Java; factual and comedic failures
are not silently waived. Reviewer-reported weak hooks trigger grounded hook reselection.
After that budget is exhausted, `MEME_SCRIPT_VALIDATION_FAILED` is **non-transient**:
Command Center marks the job FAILED rather than restarting the entire video job at 15%.
A manually requested MAKE MEME can retry later. A genuinely unreachable Ollama/HTTP
failure is reported as `OLLAMA_FAILURE` and can receive the existing delayed retry.
While queued for such a retry, the last error stays in attempt history; it is not shown as
the error of the current active attempt.

Before Ollama is called, `MemeEditorialGuard` screens source headline/description for
clear victim-centered tragedy and serious harm (including grief and fatal incidents).
It holds these stories with `MEME_EDITORIAL_REVIEW_REQUIRED` instead of automatically
joking about victims. This conservative gate is distinct from the independent meme-
worthiness ranking; contextual exceptions avoid automatically rejecting obvious
non-tragedy phrases such as "death metal". Borderline editorial assessments still
require human review.

The worker writes bounded `meme_script_diagnostics.json` alongside `meme_evidence.json`
and `script.json` in the job's output directory (the diagnostics remain even when
`script.json` was never produced). It records the source fingerprint, canonical hook,
fact IDs, attempt number, failure category, targeted rewrite/reselection and final
disposition without storing raw Ollama responses or the full article body.

The independent comedy review still adds a local Ollama call per attempted script;
targeted rewrites or hook reselection may increase generation time. Set `memeComedyMinimumScore` from 1 to 10 (default 7),
or use `memeComedyReviewEnabled=false` for diagnostics; the deterministic factual
validator always remains active. Human review is still essential for humor, fairness
and anything that could be mistaken for factual evidence.

## Validation

From a machine with Java 21:

```sh
bash build_linux.sh
java -cp build/classes autonewsroller.Main --self-test
java -cp build/classes autonewsroller.video.VisualSmoke
java -cp build/classes autonewsroller.video.PoliticalFramingSmoke
java -cp build/classes autonewsroller.video.MemeSmoke
npm run test:browser
```

On Windows compile with `build_windows.bat` or run
`batch_create_news_videos_windows.bat --self-test`. The CI tests run synthetic
CPU-only MP4 and audio effects; **they do not prove local GPU, Ollama or TTS output
quality**. Before production posting, create and inspect several real RSS story examples
on the configured Windows worker, including opening frames, phone-scale captions and
comedy audio levels.

## Limitations

- Local Ollama-generated jokes and styles require editorial review for factual fidelity,
  real comedic quality, relevance and sensitivity.
- Some RSS articles cannot be fully scraped. The evidence builder marks inaccessible
  text rather than inventing context.
- Image-consistency and style quality depend on the installed SDXL checkpoint.
- Structured meme script output checks core evidence and numerals, but is not a full
  semantic or legal fact-checker.
- The intro is an original synthesized rimshot, not a sampled comedian or laugh track.

## Independent preproduction suitability

The [MEME_WORTHINESS_ENGINE.md](MEME_WORTHINESS_ENGINE.md) scorer is an optional discovery stage that ranks RSS candidates for comedic potential before a user requests production. It does not replace the MEME_RAGEBAIT script/visual pipeline or its fact checks. High meme scores do not automatically trigger video creation; the user must still select MAKE MEME. The selected source-grounded angle is advisory, not a mandate for the production hook planner.

## Windows acceptance after update

1. Update both the Command Center and Windows worker to the same verified `main`
   commit after CI and merge; stop running workers before updating files.
2. Compile using `build_windows.bat`, then run
   `batch_create_news_videos_windows.bat --self-test`.
3. Restart `command_center_windows.bat` and `run_gpu_worker_windows.bat`.
   No new configuration key is required; keep existing Ollama and ComfyUI settings.
4. Select a source-grounded, non-tragedy RSS story and MAKE MEME / RAGEBAIT.
   The worker output folder must contain `meme_script_diagnostics.json`.
   If generation succeeds, open `script.json` and verify:
   `hook.hookText == segments[0].visualType`,
   `hook.spokenHook == segments[0].narration`, and the first rendered caption
   matches `hook.hookText`. Listen for the opening spoken line.
5. A permanently invalid script must be marked `FAILED` with
   `MEME_SCRIPT_VALIDATION_FAILURE`; a transient Ollama outage can return
   to `QUEUED`, with retry history visible in `productionAttempts`.
   Use manual MAKE MEME/REMAKE MEME only after understanding the failure.

CI uses mock Ollama, CPU media and browser tests; it cannot prove local RTX,
ComfyUI checkpoint, TTS voice or actual installed Ollama behavior. Human review
of at least three different real stories and their first frames/audio remains required.
