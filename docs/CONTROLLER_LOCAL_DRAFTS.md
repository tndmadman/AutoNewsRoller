# Controller automatic content scanning and private review

The Command Center has one persistent **automatic content scanning** toggle. ON is the default. OFF uses the same ordinary production route and archives completed media for authorized private review; it does not grant publication approval.

## Operator verification

1. Drain active jobs and stop the old worker and controller. Back up state and outputs. Fast-forward the existing integration branch in an isolated checkout and build both applications at the same validated SHA.
2. On an installation without an API token, open http://localhost:8787 on the controller. Remote setting changes and private review require the existing controller API authentication. No separate administrator token is required.
3. Change the automatic scanning setting. It is saved in `data/command_center_state.json` as `automaticSafetyScanningEnabled`. The audit history is stored in `safetySettingAudit`. A setting change requires no restart and applies to new claims, not existing claim snapshots.
4. On OFF, queue a benign test story with appropriate source rights. Ordinary production retains its existing visual requirements, mode, narration, captions, audio/video integrity checks and title-based filename. The worker transfers the completed MP4 to `output/command_center/videos/`. A completed file has `NOT_SCANNED` status and no publication approval.
5. Open **REVIEW MP4** on the completed story or latest archive version for its production format. Confirm the displayed filename and actual decoded scenes. The review route validates the controller archive record, encoded-media audit and media hash. It cannot change approval or publication status. Historical drafts retain their original files and metadata.
6. Restore ON and queue a new job. Verify the enabled detector workflow, independent controller approval, and three production-mode canaries. Old workers that lack master-setting support cannot claim OFF jobs.

## Interface and migration

- GET/POST `/api/settings/automatic-safety`: the authoritative master setting. POST requires JSON with boolean `enabled`, existing authentication or tokenless loopback access.
- The historical `/api/settings/content-safety` and `/api/settings/image-safety` URLs are aliases, not separate settings.
- `imageSafetyScanningEnabled` is a compatibility alias for the master. The old `contentSafetyScanningEnabled` claim field is retained as a normal-production routing sentinel (true for new claims); do not interpret it as a detector verdict. Actual scan execution is represented by `automaticSafetyScanningEnabled` and per-claim snapshots.
- Old saved global settings are migrated to the master. Existing claims are not rewritten. Historical isolated-draft endpoints remain for pre-upgrade claims; their original isolation and lease protections remain.
- GET `/api/review-video?versionId=...`: authenticated or tokenless-loopback private review of the current completed unscanned archive version for the selected story and mode. Missing, changed, superseded or unarchived files are rejected.
- The original `/videos/` publishable MP4 route and publication controls continue to require independent controller approval. Review is not export authorization.
- Existing detector thresholds, AIGC disclosures, mode-specific editorial requirements and media-integrity protections are not changed.

## Historical drafts

Earlier releases produced procedural, watermarked isolated drafts under `output/command_center/unscanned_drafts/`. Do not rename them to imply approval or remove their embedded provenance. Their original inspection workflow remains diagnostic only. Generate a new ordinary video for replacement.

## Validation limitations

Source and synthetic HTTP regressions do not establish real Windows/GPU rendering or pixel parity. Validate exact-head CI, real ComfyUI composition and audio/video output on operator hardware before deployment. Keep the existing PR in draft until acceptance.
