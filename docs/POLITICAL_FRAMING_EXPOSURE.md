# Political framing exposure — single-article experiment

Status: **experimental**, manual-only, isolated from the standard news production pipeline. Do not merge into main until validated with real local TTS and Ollama.

## Goal

**Expose the political framing and narrative construction inside ONE news article. Do not compare publishers.** The video dissects what the article actually says, how its headline selects or emphasizes facts, whether labels or loaded wording appear, what is presented as established versus alleged or implied, and how documented framing *might* shape the audience's interpretation.

One attributable article is sufficient. Additional reporting may substantiate a factual claim in the upstream FactPackage, but is not a subject of narration, a required input, or an excuse to create side-by-side publisher comparisons. Political slant is not presumed: an evidence-based finding of insufficient or no observable political framing is a valid conclusion.

## Command Center workflow

1. Select a story card, including a story with only one publisher or whose category is not labeled political.
2. Click **EXPOSE POLITICAL FRAMING**. The controller queues productionMode=FRAMING_EXPOSURE only when there is a source article with a headline, attribution and URL.
3. If the latest text analysis is missing or stale, the story waits for political-analysis completion. A manual request stays eligible across RSS rescans.
4. Only workers advertising framing_exposure_v1 claim experimental video jobs. If no updated worker is available, the job stays queued rather than silently falling back to a normal news video.
5. Successful output remains a distinct archived video version. Normal MAKE, WORTH, AUTO and standard remake paths remain STANDARD.

An analytical classification of not_political or uncertain **does not cancel a manually requested exposure**. Instead, the narrator must acknowledge the lack of substantiated framing or limits of the available article text.

## Spoken format

The first spoken words are the article's **exact original headline**, followed immediately by the unchangeable line:

> Stay for the analysis of how this story is being politically framed.

The original synthesized two-tone news sting plays at timestamp zero underneath that narration. The remaining six narration segments use this editorial outline:

1. Very short event/claim description, attributing single-source claims as reports instead of calling them independently verified.
2. **Headline framing:** specific terms, emphasis, agency, and what those choices foreground.
3. **Rhetorical cues:** labels, emotional language, implications and selective emphasis, but only where the supplied source material supports them.
4. **Facts vs implications:** distinguish a statement or allegation in the article from conjectures, embedded assumptions, or unproven causation.
5. **Audience effect:** how the observed wording *could* lead readers to a particular interpretation, qualified rather than asserted as fact.
6. **Evidence-based verdict:** what framing is demonstrable; what cannot be established; do not force a left/right judgment.

The writer initially aims for the minimum 75-second target but will automatically expand its nominal target when a substantive draft exceeds that budget, at approximately 2.5 words per second, capped at 180 seconds by default. A 320-word script is therefore allowed approximately 128 seconds, not forcibly shortened to 75. Duplicated filler sentences do not justify expansion. The configured ceiling is an automatic **script target ceiling**, not an audio time-stretch or cut-off; finished MP4 duration follows measured TTS audio.

Paragraphs 2–6 must account for at least 65% of narration by the script's word count; the actual TTS-aligned runtime also must meet the configured analytical threshold. Failed scripts regenerate the analytical body only and never rewrite the headline or fixed teaser. The model is explicitly prohibited from cross-publisher comparisons and unsupported assumptions about publisher motives.

## Article evidence requirements

The v2 evidence schema is **framing-evidence-v2-single-article**. It contains one selected article's source name, link, publication timestamp, exact headline, available description/body excerpts, document-scope flag, fingerprint, and only that article's analysis details. If article content is unavailable, the tool can inspect its headline but **cannot assert what the absent article body omitted**.

A URL, headline and publisher are required for attribution. Cross-source corroboration is not required to examine framing. Real original phrases can be illustrated verbatim, but the video must not invent quotes, facts, numbers, causal mechanisms, ideological labels or omissions. A newsroom-specific bias label is not a statement of the individual author's political beliefs.

Current provenance continues to include audio engine, images, visual timings, source data and the mode, with experimentalFormatVersion=framing-exposure-v2-single-article. The worker checks its source-input fingerprint against the queued job before rendering.

## Visuals and sound

The eight source-attributed editorial cards present the article headline, exact excerpt(s) where available, selected wording and written analysis, without fabricated screenshots or news organization logos. The visual types include FRAMING_HEADLINE, FRAMING_TEASER, LANGUAGE_HIGHLIGHT, RHETORIC_CARD, FACT_CHECK_CARD, FRAMING_CONTEXT and FRAMING_CONCLUSION. The restored header and 220px TikTok footer remain unchanged.

The sting uses FFmpeg-generated 784 Hz and 1046 Hz tones, faded and mixed underneath the original speech, with limiter protection. Kokoro narration and Qwen fallback are unchanged. Procedural evidence cards are used by default; this experiment does not use ComfyUI to impersonate news screenshots.

## Settings

- politicalExposureEnabled=true: controller/worker capability switch; never schedules experimental videos automatically
- politicalExposureDefaultDurationSeconds=75: minimum experimental duration target
- politicalExposureMaxDurationSeconds=180: automatic script duration may expand up to three minutes when meaningful, source-backed analysis requires it; only experimental videos use this setting
- politicalExposureMinimumIndependentSources=1: informational setting; evidence eligibility is based on one article, not an independent-source threshold
- politicalExposureRequireEvidence=true: reserved; original source attribution is always required
- politicalExposureIntroSoundEnabled=true: mandatory sting
- politicalExposureIntroSoundGainDb=-14: primary tone volume
- politicalExposureRequiredAnalysisFraction=0.65: minimum actual analytical runtime share
- politicalExposureAllowSingleSource=true: informational setting; v2 requires no cross-publisher corroboration
- politicalExposureRequireComfy=false: keep false for v2's deterministic text evidence cards

To turn the experiment off, set politicalExposureEnabled=false in controller and workers and restart them. Explicitly manage queued experimental jobs if disabling the worker capability.

## Validation and release gate

The Java fixture checks one-source enqueue/claim, worker capability protection, state restart, archived versions, safe standard remake, exact headline+teaser ordering, per-article evidence scoping, and nonpolitical single-source requests surviving rescans. Playwright verifies that the new button exists even when a story is not automatically political and has only one source.

PoliticalFramingSmoke renders eight v2 framing cards with an actual FFmpeg synthetic-audio MP4 and checks the intro mix, output duration and restored bottom footer. It does **not** test the natural-language model's judgment or real TTS audio. The standard Java, Linux/Windows video, and Playwright regression suites must pass.

**Before merging**, generate and inspect an actual 70–80 second narrated experiment on the Windows GPU worker with the real Ollama model, Kokoro or Qwen, FFmpeg and captions. Review factual support, exact headline, teaser, original intro sound, source quotations, actual framing analysis versus generic filler, TikTok footer, and narration duration. A synthetic CI fixture is necessary but insufficient.

This implementation is meant to expose *observable framing*, not to assert that every news outlet is biased or that a story contains manipulation when the evidence does not support it.
