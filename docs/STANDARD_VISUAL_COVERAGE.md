# STANDARD visual coverage — scoped implementation

Starting revision: `eb19702373f78fa196970eb6d2090ad151d670ef`. Existing PR #61.

Confirmed before change: whole-story sensitive routing skips ComfyUI; eligible scene exclusions omitted STATISTIC, QUOTE and TIMELINE; FACT_HIGHLIGHT was secondary; count was a ceiling; generated-image overlays used the generic story layout. This change targets ordinary STANDARD stories only. The existing sensitive-story evidence-only policy, diagnostic draft path, scanner settings, approval, disclosure, MEME and FRAMING behavior are retained.

Validated fact-cited scene prompts determine eligibility. Reserved/supplemental/source graphics and sensitive or unvalidated scenes are not generated. Eligible information scenes use existing factual graphic painters on a translucent panel, while the existing safety, retry, checkpoint and asset gates remain active. The target is the smaller of configured budget and eligible scenes; selection is distributed over narration including the first eligible scene. A required image cannot be silently replaced. If no eligible scene remains on an illustration-required story, existing failure behavior applies.

Scene decisions, scheduled count, accepted sources and exact encoded timeline-frame allocation are reported in visual_quality_report.json and provenance. Timeline coverage is not a decoded-frame identity measurement. No new publication eligibility is implied.

Regression fixtures added to VisualAssetGateSelfTest: 8 primary scenes / 6 target, reserved source card, rejected invalid prompt, unchanged evidence-only route, correct frame coverage, required schedule mismatch, translucent graphic overlay.

Limitations: whole-story sensitive routing is intentionally unchanged; safe contextual illustration inside sensitive stories and ambiguity disambiguation are not implemented here. No real ComfyUI, physical Windows GPU, full build or encoded-image identity acceptance was performed by the authoring environment. CI and hardware acceptance are outstanding. No measured performance improvement is claimed.
