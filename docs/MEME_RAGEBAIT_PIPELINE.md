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
3. `MemeScriptGenerator` uses Ollama to brainstorm/select comedy angles, then calls
   `MemeHookPlanner` to rank four or more fact-ID-grounded frame-zero caption/spoken
   openings. The selected hook is locked into variable-length script generation
   with cited fact IDs and explicit satirical visual descriptions. Use an ordinary safe fallback only for **dry runs**.
4. `MemeScriptValidator` checks basic narration, first-hook brevity, cited fact ID
   presence, generic filler and unsupported numerical claims. Satire is not proof; operators
   still need human review for quotation fidelity, fairness and joke quality.
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

A 220px scaled dark footer is preserved for platform overlays; standard-news header,
ticker and TikTok-safe behavior remain separate.

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
memeUseComfy=true
memeRequireComfy=true
memeComfyImages=3
memeIntroSoundEnabled=true
memeIntroSoundGainDb=-10
```

ComfyUI defaults to required; when it is unavailable or cannot produce acceptable images,
the job fails rather than silently claiming it generated AI illustrations. Local device
paths and checkpoint settings are inherited from `defaults.txt`.

## Failure handling and provenance

Story and final-video sidecars preserve mode, source URLs, fingerprints, script and angle,
hook candidate scores and rejection diagnostics, scene timing, actual voice, encoder,
Comfy checkpoint/seeds/image paths, the original synthesized sound identity and gain, and
auditing results. The controller counts both historic `comfyui-generated` and current
`comfyui-generated-ai-illustration` image provenance types.

Existing automatic retries and lease protections apply. Invalid Ollama JSON or scripts
produce explicit stage errors, rather than fabricated success. Human review should check
that outrageous satire does not become a defamatory claim or present AI art as evidence.

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
