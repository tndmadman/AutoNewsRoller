# Controller content safety: isolated local drafts

The Command Center has a persistent administrator-only **Content Safety Scanning** toggle. Default is ON, including installations upgraded from a state file without this field. OFF means diagnostic drafts only; it does not change the normal export approval requirements.

## Windows operator verification

1. Drain workers and stop the old controller. Back up state and output, and use an isolated checkout for verification. Fast-forward the existing integration branch, build, and run controller and workers at the same SHA. Do not deploy to production without CI and hardware acceptance.
2. In the environment of the controller process, set a separate high-entropy administrator token using your normal secret management method (environment variable AUTONEWS_ADMIN_TOKEN). Do not commit it to defaults.txt or reuse the worker/API token. Use localhost or an authenticated, trusted encrypted connection.
3. Start the controller, open its Command Center, and authenticate with the existing API token if configured. In the content safety panel, click the toggle, accept the local-only warning, and enter the separate admin token. The setting is saved in data/command_center_state.json as contentSafetyScanningEnabled; its change history is saved in safetySettingAudit. No further controller restart is needed to change the setting.
4. Start the updated worker, queue a test story with appropriate source rights, and inspect the worker console. OFF renders a procedural-only diagnostic video with a permanent NOT SCANNED / LOCAL DRAFT ONLY label. It uses no generated ComfyUI images and no detector initialization. Normal non-scanner dependencies (Ollama, TTS, FFmpeg) still apply.
5. Locate the absolute worker-local output path on the controller story card (HOLD / NOT_SCANNED) or worker job history. Open the file on that worker in your local player. It is outside final_videos and is not uploaded, served by the controller's /videos route, or added to the approved version archive. Existing approved archives remain governed by the independent approval gate.
6. Restore ON via the toggle, then queue a new job. Existing leases retain the mode captured at claim time; new or recovered claims receive current policy. Updated workers receive the current state on heartbeat. Old workers cannot claim OFF-mode video jobs.
7. To inspect an existing local draft after restoring ON, use this command from the worker repository folder, with AUTONEWS_TOKEN set if the controller uses an API token (otherwise use AUTONEWS_ADMIN_TOKEN for the authenticated settings read):

       java -cp build\classes autonewsroller.safety.LocalDraftInspection http://127.0.0.1:8787 "C:\path\to\NOT_SCANNED_example.mp4"

   Substitute the trusted controller's actual URL and the draft path. The command validates the isolated path and original hash, checks that scanning is ON, and executes both existing pinned scanners. It writes a separate .inspection.json diagnostic report, not an export approval; the original draft is never promoted. To obtain publishable media, generate a normal ON-mode job and obtain the full worker and independent controller approvals.

## Interfaces

- GET /api/settings/content-safety: configured API token or administrator authentication on a no-API-token controller; returns contentSafetyScanningEnabled.
- POST /api/settings/content-safety: configured API token if applicable, separate X-AutoNews-Admin-Token, and JSON boolean enabled. OFF also requires confirmLocalOnly=true.
- Worker claim settings and heartbeat replies contain contentSafetyScanningEnabled. Workers advertise local_draft_v1.
- POST /api/jobs/{id}/local-draft-complete: lease/epoch-fenced worker metadata only, including localPath and videoSha256. Does not accept or publish MP4 bytes. Ordinary video upload and completion routes reject local-only claims.
- Historical detector policy, thresholds, coverage, CPU/GPU backends, controller rescans, and AIGC disclosure requirements are unchanged for normal jobs.

## Validation limitations

The local draft path is intentionally procedural-only and has no controller-hosted player or automatic promotion/rescan queue. Inspection is a manual worker-local diagnostic operation. Physical Windows/GPU tests, real local rendering with CUDA/parity files absent, and three-mode canaries require operator hardware; synthetic HTTP and path tests do not establish that those tests passed. Retain draft PR status until exact-head CI and operator verification pass.
