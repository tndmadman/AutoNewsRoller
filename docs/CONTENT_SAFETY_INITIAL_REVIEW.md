# Initial content-safety investigation — 2026-10-28

Base: 8b1868dde714d15c3244ef49b50ae77955cd54bf. This is the pre-edit review.

Confirmed in base:
- NewsPipeline.java generated Comfy images and used ImageQualityInspector plus optional OllamaVisionReviewer. Neither was a mandatory explicit-anatomy classifier.
- NewsPipeline.java immediately copied a rendered MP4 into final_videos without a full-frame nudity audit or hash-bound safety approval.
- ComfyPromptProfiles.java and GroundedVisualPromptGenerator.java instructed synthetic images to resemble wire-service documentary photography.
- CommandCenterServer.java accepted uploaded MP4 bytes and CommandCenterStore.complete marked them COMPLETE without validating a safety manifest against the uploaded bytes.
- Current AI labels varied by card and were not enforced as a complete post-render invariant.
- Old final_videos and server videos were not treated as unverified when presented.

Known limits and non-conclusions:
- The exact TikTok AIGC enforcement reason remains unknown. No conclusion is made about its relationship to the nudity violation.
- NudeNet 3.4.2 is MIT-licensed and accepts OpenCV images. This candidate dependency must still be installed, run on the Windows GPU worker, and calibrated using a licensed real-image evaluation set.
- Existing worker/controller split prevents treating a worker-supplied boolean as sufficient upload evidence.
- Full frame audit cost requires real performance measurement. This branch must not be merged without validating Windows and realistic footage.
