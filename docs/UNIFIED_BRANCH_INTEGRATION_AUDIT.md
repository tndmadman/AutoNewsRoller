# Unified branch integration audit

**Date:** 2026-11-17  
**Repository:** `tndmadman/AutoNewsRoller`  
**Integration:** `integration/autonewsroller-unified-20261117`  
**Verified source HEAD:** `4128294c6b6fcb04a6a99a848265da6bd4c0f4c0`  
**Verified main:** `8b1868dde714d15c3244ef49b50ae77955cd54bf`  
**Five-branch integration:** `4493de9cf6c537553baa02eb37ba645fce48b89b`

## Ancestry and rules

GitHub comparison confirms recovery **190 commits ahead of main, 0 behind**, with main as the merge base. Recovery is **39 ahead of the five-branch integration, 0 behind**. The unified branch was created from the exact recovery SHA, without altering `main`.

All 61 remote branch names were compared directly to the verified recovery head. The comparison below is a **commit-graph inventory**, not a claim that every unique patch is semantically absent. **Branch-only** counts indicate commits present in that branch but absent from recovery; **recovery-only** counts indicate the reverse. Historical merged PRs can diverge in Git history and still be semantically represented in the current code. Never blindly merge them again.

## Complete branch disposition matrix

| Branch | Observed head SHA (short) | Related PR | Branch-only / recovery-only commits | Decision |
|---|---|---|---:|---|
| `docs/full-project-documentation` | `9e9fb1bec1a7` | [#2](https://github.com/tndmadman/AutoNewsRoller/pull/2) merged | 0 / 718 | Already in recovery ancestry; no merge |
| `feature/aware-broadcast-v4` | `076dc8fb50fc` | [#30](https://github.com/tndmadman/AutoNewsRoller/pull/30) merged | 0 / 442 | Already in recovery ancestry; no merge |
| `feature/command-center` | `4eafd7a9a0a2` | [#5](https://github.com/tndmadman/AutoNewsRoller/pull/5) merged | 0 / 655 | Already in recovery ancestry; no merge |
| `feature/command-center-auto-video-toggle` | `e8b64eba7452` | [#29](https://github.com/tndmadman/AutoNewsRoller/pull/29) merged | 0 / 451 | Already in recovery ancestry; no merge |
| `feature/command-center-local-ollama-assistant` | `20b21f042f7c` | [#50](https://github.com/tndmadman/AutoNewsRoller/pull/50) merged | 0 / 202 | Already in recovery ancestry; no merge |
| `feature/command-center-retro-terminal-ui` | `02a25bf05f22` | [#38](https://github.com/tndmadman/AutoNewsRoller/pull/38) merged | 0 / 348 | Already in recovery ancestry; no merge |
| `feature/content-safety-aigc-fail-closed-20261028` | `4cd6ea02b26b` | [#55](https://github.com/tndmadman/AutoNewsRoller/pull/55) open | 0 / 109 | Already in recovery ancestry; no merge |
| `feature/expand-rss-sources` | `1dde47a31cc8` | [#4](https://github.com/tndmadman/AutoNewsRoller/pull/4) merged | 0 / 696 | Already in recovery ancestry; no merge |
| `feature/finish-production-video-quality` | `d20a8c7024bf` | [#11](https://github.com/tndmadman/AutoNewsRoller/pull/11) merged | 0 / 586 | Already in recovery ancestry; no merge |
| `feature/grounded-visual-prompts` | `2a3aa67c7721` | [#27](https://github.com/tndmadman/AutoNewsRoller/pull/27) merged | 0 / 469 | Already in recovery ancestry; no merge |
| `feature/independent-meme-worthiness-engine` | `c2fb99af60c0` | [#46](https://github.com/tndmadman/AutoNewsRoller/pull/46) merged | 0 / 251 | Already in recovery ancestry; no merge |
| `feature/manual-upload-tracking` | `6006905e0860` | [#17](https://github.com/tndmadman/AutoNewsRoller/pull/17) merged | 0 / 553 | Already in recovery ancestry; no merge |
| `feature/meme-comedy-writing-quality-gates` | `bc920c456e51` | [#48](https://github.com/tndmadman/AutoNewsRoller/pull/48) merged | 0 / 230 | Already in recovery ancestry; no merge |
| `feature/meme-discovery-analysis-filters` | `eb6949cf2f8b` | [#47](https://github.com/tndmadman/AutoNewsRoller/pull/47) merged | 0 / 240 | Already in recovery ancestry; no merge |
| `feature/natural-video-color` | `99e58a0bc15f` | [#26](https://github.com/tndmadman/AutoNewsRoller/pull/26) merged | 4 / 485 | Historical merged PR; reconcile by content, no blind merge |
| `feature/news-channel-video-package` | `f7de69d61bb3` | [#24](https://github.com/tndmadman/AutoNewsRoller/pull/24) merged | 0 / 496 | Already in recovery ancestry; no merge |
| `feature/news-video-pipeline` | `326bb53c12a6` | [#1](https://github.com/tndmadman/AutoNewsRoller/pull/1) merged | 0 / 731 | Already in recovery ancestry; no merge |
| `feature/photoreal-comfy-defaults-3090` | `95a3cede0421` | [#44](https://github.com/tndmadman/AutoNewsRoller/pull/44) merged | 1 / 262 | Historical merged PR; reconcile by content, no blind merge |
| `feature/political-context-graphics` | `00e5dc39acfd` | [#25](https://github.com/tndmadman/AutoNewsRoller/pull/25) merged | 18 / 486 | Historical merged PR; reconcile by content, no blind merge |
| `feature/political-framing-analysis` | `a1ab2dc6ffb7` | [#9](https://github.com/tndmadman/AutoNewsRoller/pull/9) merged | 0 / 600 | Already in recovery ancestry; no merge |
| `feature/political-framing-exposure-video` | `103fe9d53674` | [#42](https://github.com/tndmadman/AutoNewsRoller/pull/42) merged | 0 / 263 | Already in recovery ancestry; no merge |
| `feature/recent-found-meme-discovery-v2` | `59df3c7695ef` | [#49](https://github.com/tndmadman/AutoNewsRoller/pull/49) merged | 0 / 215 | Already in recovery ancestry; no merge |
| `feature/retention-hook-system` | `9318afe58fce` | [#21](https://github.com/tndmadman/AutoNewsRoller/pull/21) merged | 0 / 511 | Already in recovery ancestry; no merge |
| `feature/rss-meme-ragebait-pipeline` | `30cf7adca7b0` | [#43](https://github.com/tndmadman/AutoNewsRoller/pull/43) merged | 13 / 262 | Historical merged PR; reconcile by content, no blind merge |
| `feature/rss-publisher-diversity-20261016` | `d21afc1906fc` | [#35](https://github.com/tndmadman/AutoNewsRoller/pull/35) merged | 0 / 385 | Already in recovery ancestry; no merge |
| `feature/story-operations-pagination` | `fa4ce6ebf51e` | [#37](https://github.com/tndmadman/AutoNewsRoller/pull/37) merged | 0 / 360 | Already in recovery ancestry; no merge |
| `feature/video-quality-overhaul` | `19ab511364e3` | [#10](https://github.com/tndmadman/AutoNewsRoller/pull/10) open | 31 / 599 | Open legacy PR #10; selective port only |
| `feature/video-remake-scrap-lifecycle` | `30f714c7b9f2` | [#18](https://github.com/tndmadman/AutoNewsRoller/pull/18) merged | 0 / 543 | Already in recovery ancestry; no merge |
| `feature/video-visual-quality-v5` | `1ab1e59e6795` | [#34](https://github.com/tndmadman/AutoNewsRoller/pull/34) merged | 0 / 390 | Already in recovery ancestry; no merge |
| `feature/windows-worker-web-console` | `62ba89e43783` | [#39](https://github.com/tndmadman/AutoNewsRoller/pull/39) merged | 0 / 340 | Already in recovery ancestry; no merge |
| `feature/worthy-hour-filter` | `11bac33a0df3` | [#19](https://github.com/tndmadman/AutoNewsRoller/pull/19) merged | 0 / 537 | Already in recovery ancestry; no merge |
| `fix/blacklist-kokoro-nicole` | `78b92839a2a5` | [#22](https://github.com/tndmadman/AutoNewsRoller/pull/22) merged | 0 / 505 | Already in recovery ancestry; no merge |
| `fix/comfy-explosion-conditioning` | `79c9cd720c51` | [#28](https://github.com/tndmadman/AutoNewsRoller/pull/28) merged | 0 / 458 | Already in recovery ancestry; no merge |
| `fix/comfyui-shared-image-quality-20261023` | `61eed9256542` | [#45](https://github.com/tndmadman/AutoNewsRoller/pull/45) merged | 12 / 260 | Historical merged PR; reconcile by content, no blind merge |
| `fix/command-center-comfy-tts-reporting` | `82738606fc56` | [#6](https://github.com/tndmadman/AutoNewsRoller/pull/6) merged | 0 / 645 | Already in recovery ancestry; no merge |
| `fix/command-center-interaction-stability` | `70becdc12382` | [#33](https://github.com/tndmadman/AutoNewsRoller/pull/33) merged | 0 / 425 | Already in recovery ancestry; no merge |
| `fix/distinguish-rss-feed-cards` | `c513c10e78aa` | [#23](https://github.com/tndmadman/AutoNewsRoller/pull/23) merged | 0 / 501 | Already in recovery ancestry; no merge |
| `fix/failed-age-filter` | `836de1861e56` | [#32](https://github.com/tndmadman/AutoNewsRoller/pull/32) merged | 0 / 427 | Already in recovery ancestry; no merge |
| `fix/failed-production-lifecycle` | `f15c0a1f377c` | [#31](https://github.com/tndmadman/AutoNewsRoller/pull/31) merged | 0 / 430 | Already in recovery ancestry; no merge |
| `fix/manual-make-force-queue` | `eb5a0b04eb8f` | [#8](https://github.com/tndmadman/AutoNewsRoller/pull/8) merged | 0 / 620 | Already in recovery ancestry; no merge |
| `fix/meme-production-not-stalled-by-comedy-review` | `4e774845c736` | [#54](https://github.com/tndmadman/AutoNewsRoller/pull/54) merged | 0 / 183 | Already in recovery ancestry; no merge |
| `fix/meme-script-hook-contract-reliability` | `2d8f09623f5d` | [#52](https://github.com/tndmadman/AutoNewsRoller/pull/52) merged | 0 / 174 | Already in recovery ancestry; no merge |
| `fix/narrative-reviews-on-demand-20261110` | `fe3d32a23dfd` | [#59](https://github.com/tndmadman/AutoNewsRoller/pull/59) merged | 0 / 40 | Already in recovery ancestry; no merge |
| `fix/ollama-chat-relevance-enter-to-send` | `39f76062f083` | [#51](https://github.com/tndmadman/AutoNewsRoller/pull/51) merged | 0 / 194 | Already in recovery ancestry; no merge |
| `fix/ollama-meme-continuation-and-framing-output` | `095cb3f0e4b1` | [#53](https://github.com/tndmadman/AutoNewsRoller/pull/53) merged | 0 / 182 | Already in recovery ancestry; no merge |
| `fix/ollama-script-length-enforcement` | `82f12dff22e4` | [#13](https://github.com/tndmadman/AutoNewsRoller/pull/13) merged | 0 / 577 | Already in recovery ancestry; no merge |
| `fix/requeue-failed-video-jobs` | `3df788f502c4` | [#16](https://github.com/tndmadman/AutoNewsRoller/pull/16) merged | 0 / 562 | Already in recovery ancestry; no merge |
| `fix/require-real-visuals-all-video-pipelines-20261028` | `b0b8c46bcfbf` | [#56](https://github.com/tndmadman/AutoNewsRoller/pull/56) open | 0 / 180 | Already in recovery ancestry; no merge |
| `fix/rss-feed-reliability-20261016` | `3f1a616ff2d3` | [#36](https://github.com/tndmadman/AutoNewsRoller/pull/36) merged | 0 / 383 | Already in recovery ancestry; no merge |
| `fix/rss-full-scan-watch-batch` | `133a4bac6c19` | [#3](https://github.com/tndmadman/AutoNewsRoller/pull/3) merged | 0 / 710 | Already in recovery ancestry; no merge |
| `fix/script-generation-reliability` | `ea96d46bce0c` | [#20](https://github.com/tndmadman/AutoNewsRoller/pull/20) merged | 0 / 526 | Already in recovery ancestry; no merge |
| `fix/script-word-count-prompt` | `9db9323612da` | [#12](https://github.com/tndmadman/AutoNewsRoller/pull/12) merged | 0 / 584 | Already in recovery ancestry; no merge |
| `fix/targeted-script-expansion-retries` | `1e3961965f8f` | [#14](https://github.com/tndmadman/AutoNewsRoller/pull/14) merged | 0 / 572 | Already in recovery ancestry; no merge |
| `fix/tiktok-original-layout-bottom-footer` | `79b49e88db6d` | [#41](https://github.com/tndmadman/AutoNewsRoller/pull/41) merged | 10 / 337 | Historical merged PR; reconcile by content, no blind merge |
| `fix/tiktok-safe-video-layout` | `1b2882223abc` | [#40](https://github.com/tndmadman/AutoNewsRoller/pull/40) merged | 4 / 339 | Historical merged PR; reconcile by content, no blind merge |
| `fix/video-failure-recovery-resource-optimization-20261111` | `4128294c6b6f` | [#60](https://github.com/tndmadman/AutoNewsRoller/pull/60) open | 0 / 0 | Recovery source; exact starting point |
| `fix/worker-media-reliability` | `e54918d5721c` | [#7](https://github.com/tndmadman/AutoNewsRoller/pull/7) merged | 0 / 631 | Already in recovery ancestry; no merge |
| `fix/worthy-workflow-filter` | `e13170895589` | [#15](https://github.com/tndmadman/AutoNewsRoller/pull/15) merged | 0 / 568 | Already in recovery ancestry; no merge |
| `integration/autonewsroller-5branch-illustrated-safe-retry-20261107` | `4493de9cf6c5` | [#58](https://github.com/tndmadman/AutoNewsRoller/pull/58) open | 0 / 39 | Already in recovery ancestry; no merge |
| `integration/autonewsroller-unified-20261117` | `3657b8210353` | — | 8 / 0 | Unified work branch; 8 new commits at audit snapshot |
| `main` | `8b1868dde714` | — | 0 / 190 | Protected base; unchanged |

## Open PR reconciliation

- **PR #55:** content-safety v3, mandatory NudeNet 3.4.2 and OpenNSFW2 0.19.0, independent final-frame scan, AIGC overlay, policy- and hash-bound export manifests. Its head is ancestral to recovery.
- **PR #56:** generated-image requirement and evidence-graphic gate. Its head is ancestral to recovery.
- **PR #58:** five-branch integration and illustrated retries; directly ancestral to recovery.
- **PR #59:** on-demand Narrative Review, previously merged into the integration ancestry; its head is ancestral to recovery. Preserve `commandCenterPairFramingVideos=false`.
- **PR #60:** durable workspaces, preflight, retry policy, checkpoints, and initial resource optimization; exact starting parent of unified.
- **PR #10:** old video-quality code is **31 branch-only commits** against recovery. Do not wholesale merge obsolete renderer APIs. Current defaults already specify `targetDurationSeconds=70`, `videoFps=30`, `visualImageReviewRetries=4`. Current renderer includes modern caption/footer and motion architecture. The missing encoded-media checks were ported in the unified branch. Live hardware acceptance tooling needs a current-API implementation.
- Other divergent heads: natural video color, photoreal Comfy defaults, political context graphics, RSS meme pipeline, Comfy image quality, and two TikTok footer branches all correspond to merged PRs. Their historical tips have unique graph ancestry but are superseded or represented by current code. Check any proposed individual feature against the current safety architecture, not just commit uniqueness.
- Previously merged #52, #53, #54 and older PRs remain represented by `main`/recovery; do not re-apply patches.

## Current integration edits

- `src/autonewsroller/audit/VideoAudit.java`: encoded MP4 dimensions, video/audio codecs and streams, required non-silent encoded audio, configured CFR, measured narration, and audio/video duration tolerance.
- `src/autonewsroller/orchestration/NewsPipeline.java`: calls media audit with configured FPS; uses bounded attempts; records prompt, seed, rejection reason and accepted status only after composed safety checks.
- `src/autonewsroller/visuals/VisualPlanner.java`: removes realistic-news-photograph fallback.
- `src/autonewsroller/visuals/ImageRetryCoordinator.java`: deterministic, five-attempt maximum (one original and up to four alternatives).
- `src/autonewsroller/visuals/ImageRetryCoordinatorSelfTest.java` and `src/autonewsroller/audit/VideoAuditSelfTest.java`: regression tests.
- `.github/workflows/windows-validation.yml`: runs the new regressions under Windows and Linux.

## Still required before release

1. Exact-final-head CI must be green: Windows Java and scanner setup, Linux headless tests, archive regression, Python detectors, FFmpeg safety smoke, Chromium and Firefox Playwright. Failed/skipped checks remain blockers.
2. Test all three modes with actual Windows RTX 3090-class hardware and installed ComfyUI, SDXL, Ollama, Kokoro/Qwen, FFmpeg, and both safety detectors. Inspect generated videos at phone size. This access is not available to repository-only tooling.
3. Calibrate detector thresholds on lawful, licensed evaluation fixtures and verify independent full-frame audit performance.
4. Complete real controller/worker fault injection for lease takeover, cancellations, worker crash, corrupt checkpoint, interrupted rendering, duplicate completion, and upload acknowledgement.
5. Complete stage telemetry, measured benchmark, current-API live acceptance harness, and written deployment/rollback evidence.
6. Validate actual historical media archives in a protected test workspace. Do not delete production outputs or modify operating state for tests.
7. Confirm review and release approvals. Maintain **draft** state until required evidence exists.

## Protection status

No merge into `main`, force push, branch deletion, production deployment, or historical MP4 deletion was performed. This audit records the branch graph as observed at `3657b8210353` before this documentation commit.
