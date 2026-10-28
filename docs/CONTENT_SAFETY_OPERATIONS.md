# Content safety / AIGC transition (DRAFT — NOT YET PRODUCTION VALIDATED)

## Hard policy

The worker scans every generated image and every composed background before use. Failure, timeout and unavailable scanner do not mean SAFE; rejected image attempts are deleted and the untouched Java procedural card remains in place. The final render is decoded frame by frame and scanned; the MP4 is only copied into final_videos after a content-addressed manifest reaches APPROVED_FOR_EXPORT. Worker and controller independently check the exact MP4 SHA-256 against that manifest before offering it in the normal posting/download path.

Sensitive stories (war, allegations, sexual violence, minors, medical emergencies, disasters) are restricted to existing Java procedural source/fact graphics. FRAMING_EXPOSURE does not invoke ComfyUI. STANDARD and MEME_RAGEBAIT have common mandatory negative conditioning; the standard prompt profile no longer requests synthetic wire-service documentary images.

The final FFmpeg render overlays a persistent, phone-readable "AI-GENERATED VISUALS / AI NARRATION" label and "ILLUSTRATION - NOT ACTUAL FOOTAGE"; MEME_RAGEBAIT retains SATIRE labeling. A colored marker makes it possible to assert every decoded frame has the overlay without relying on OCR. This alone does not verify every word's legibility.

## Installation on the GPU worker

Use the Python interpreter configured by contentSafetyPython and install:

    python -m pip install -r requirements-content-safety.txt

For the Windows portable Python used by ComfyUI, do not assume packages are installed in the same interpreter; explicitly set contentSafetyPython to the correct compatible Python executable.

Default NudeNet 3.4.2 ships a 320n ONNX model and runs CPU-only. Java calls Python as a subprocess. First deployment must verify the model loads, that every expected exposed-anatomy class is returned on licensed test material, and that a whole video completes within the configured timeout. Because it uses CPU ONNX, it does not contend for the ComfyUI GPU lane. A future persistent scanner process would reduce startup overhead.

A detector failure fails closed: no MP4 approval. Attempts to disable a mandatory production safety step via configuration are intentionally unsupported. The detector's preliminary 0.18 exposed-anatomy threshold is **not calibrated** and must not be treated as a measured false-negative guarantee.

## Operations

- To review historical local videos, run: python tools/audit_historical_outputs.py --root .
- The audit is read-only by default; its JSON reports go to output/content_safety.
- Add --quarantine-confirmed-unsafe only if moving detector-confirmed unsafe material out of the usual output directory is intended. Borderline/unverified media are not deleted.
- Existing video versions are NOT_SCANNED, not automatically grandfathered into approval. Historical MP4s cannot use normal Command Center OPEN MP4/download and posting controls without a current matching approval.
- Uploads from older workers without a safety manifest will fail at controller completion.
- TikTok's creator-side AIGC switch must be enabled by the operator; the MP4 overlay and metadata do not enable that setting.
- Publishing description: "AI-generated visuals and synthesized narration. Illustrations are not footage of the reported event. Reporting based on the attributed news sources." Append the article publisher names and URLs.

## Remaining release blockers

1. Validate NudeNet 3.4.2 installation, Windows subprocess behavior, FFmpeg and GPU worker behavior.
2. Evaluate license-approved adult/nonsexual examples and difficult exposure cases; report precision, false positives, false negatives and category-specific thresholds.
3. Add an independent classifier for erotic poses/sexual context and challenging illustrations; NudeNet exposed-region boxes alone cannot identify all prohibited content.
4. Benchmark full-frame, four-region scanning on the Threadripper workstation. It may be prohibitively slow with a new interpreter per still image.
5. Validate the disclosure marker persists through real FFmpeg encodes and is legible without headline/caption collisions. Verify source grounding of synthetic scenes and the three modes end to end.
6. Add production-safe retries for positive prompts rejected on news sexual-content wording, archival reapproval controls, and more complete health/operations UI as needed.

The TikTok account-ban mechanism and its relationship (if any) to the nudity strike remain unverified.
