# Political framing exposure — isolated experiment

Status: **experimental**. Manually triggered only. Do not merge into main before full production-machine validation.

## Operator workflow

Start the current Command Center and at least one updated GPU worker. Select a politically relevant story with at least two attributable independent publishers, then click **MAKE POLITICAL FRAMING EXPOSURE VIDEO** on that story card.

The Command Center persists production mode **FRAMING_EXPOSURE**. Missing or stale political analysis causes a queued wait with the **WAITING_FOR_FRAMING_ANALYSIS** stage. If a compatible worker is unavailable, the video remains queued with **WAITING_FOR_COMPATIBLE_WORKER**. Ordinary videos remain independently claimable.

When a capable worker claims an experiment, it reads the server-controlled mode and verifies the analysis content fingerprint. A completed experimental video becomes its own archived video version. Each archived version retains independent upload, scrap, and format information. A later normal MAKE VIDEO, WORTH IT, or ordinary RE-MAKE always uses **STANDARD**.

## What the video contains

Narration starts immediately with the source headline, then this exact fixed sentence:

> Stay for the analysis of how this story is being politically framed.

Both lines are inserted in Java, not entrusted to the language model. The subsequent six beats explain the event, the different source headlines, supported distinctions in wording, how emphasis may influence interpretation, and evidentiary limits. If TTS duration is too short, the generator repairs only the analytical body. A dedicated validator checks word ranges, exact opening, grounded numbers and quotes, publisher attributions, and analytical share. SpeechTiming's measured segment durations must also show the configured analytical fraction.

The intro sound is an original FFmpeg-synthesized two-tone sting (784/1046 Hz, 1.3 seconds) mixed at timestamp zero under the unshifted narration and limited to prevent clipping. The experimental render uses the same existing TTS choices (Kokoro, then Qwen), video encoder, and caption timing sidecar as standard videos, but does not alter normal audio.

Eight procedural news cards display exact attributed source text, not fabricated screenshots or publisher mastheads. The current AWARE header, ticker, caption safe region and opaque 220-pixel TikTok post-text footer are preserved. The visual heading is **NEWS ANALYSIS**; the sting does not assert that older news is genuinely breaking.

## Evidence and limitations

The separate evidence schema is framing-evidence-v1. It records article ID, publisher, article URL and time, exact attributed headline, supplied descriptions/body excerpts, content fingerprints, and political-analysis metadata. At least two publishers must describe an apparently shared event using distinct headlines. Same-event matching in v1 uses a conservative lexical overlap heuristic, not a fully independent semantic verification step. Review comparisons before publication.

The political classification from Ollama is an uncertain interpretation of supplied text, **not** a publisher truth score or evidence of deliberate manipulation. Baseline publisher classifications remain separate metadata. In this version, the framing inspection emphasizes documented headline language rather than full-text omissions; it must not invent missing context, partisan motives, quotations, numerical claims, or unsupported source ratings. Machine checks cannot establish all subtleties of framing; **editorial review is required**.

The default experiment uses deterministic text evidence cards rather than ComfyUI generated pictures to avoid suggesting fabricated headlines are real screenshots. A configuration requiring ComfyUI for this version will cause an explicit failure, not produce a fake image.

## Settings

The following keys are optional in defaults.txt:

| Key | Default | Meaning |
|---|---|---|
| politicalExposureEnabled | true | Worker/controller feature switch; does not auto-create experimental jobs |
| politicalExposureDefaultDurationSeconds | 75 | Minimum experimental target |
| politicalExposureMinimumIndependentSources | 2 | Minimum for cross-source comparison |
| politicalExposureRequireEvidence | true | Reserved; evidence is unconditionally required in v1 |
| politicalExposureIntroSoundEnabled | true | Required sound; disabling it fails experiment |
| politicalExposureIntroSoundGainDb | -14 | First tone mix gain; second is 5 dB lower |
| politicalExposureRequiredAnalysisFraction | 0.65 | Minimum TTS-timed analytical share |
| politicalExposureAllowSingleSource | false | Reserved; single-source mode unsupported in v1 |
| politicalExposureRequireComfy | false | Keep false; version 1 uses evidence cards |

To disable, set politicalExposureEnabled=false for Command Center and workers and restart them. Standard video production is unaffected. Explicitly handle any existing experimental pending jobs rather than waiting for a capability that has been turned off. Do not modify standard hooks or unrelated production configuration.

## Recovery and regression validation

Repeated experiment actions share the existing operation ID protection. The store rejects conflicting queued or producing actions. Failed analysis is visible and can be requeued from **ANALYZE FRAMING**. Worker loss, controller restart, and automatic retry preserve the experimental mode. Unknown mode values raise errors instead of silently falling back.

Run the existing Windows self-test batch, Java SelfTest, WorkerConsoleSelfTest, the Playwright browser tests and VisualSmoke when FFmpeg is present. A deterministic framing fixture regression and a new guarded-button browser test are also included.

**Production rollout gate:** the actual framing MP4 must still be rendered and inspected with the Windows GPU worker, local Ollama, Kokoro/Qwen, and FFmpeg. Check that the headline and fixed teaser are audible, the two-tone sting is mixed beneath the start, source comparisons are legible, captions track speech, the full 220-pixel lower safe area remains intact, video duration exceeds the configured minimum, and the sidecar contains the experimental provenance. Normal FFmpeg smoke tests passing are not proof that this integrated experiment rendered successfully.

This experiment is to remain isolated on feature/political-framing-exposure-video until that validation is complete.
