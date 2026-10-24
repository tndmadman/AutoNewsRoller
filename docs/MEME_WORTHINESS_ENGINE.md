# Independent meme-worthiness engine

The **MEME DISCOVERY** Command Center rail is an editorial discovery view, not a news truth score and not a prediction of virality. This engine operates independently of the existing StoryRanker, WORTHY decisions, political framing analysis, and the MEME_RAGEBAIT video pipeline.

## Discovery and evaluation

On each completed RSS scan the controller computes a cheap deterministic priority score from every eligible **FOUND** RSS story (not just normal WORTHY stories). Selection now prioritizes fresh stories from a configurable eight-hour lookback window. By default, 90% of each scan's new analysis slots are reserved for those recent stories if enough exist, while older unscored stories receive the remainder; unused slots flow to the other pool. Within each pool, 25% of slots are reserved for below-prefilter-threshold exploration when such stories exist, **even if high-prefilter candidates could fill the whole budget**. This prefilter is a scheduling aid, never a final judgment of humor. Every candidate is fingerprinted from its identity, attributable source URLs, article titles, descriptions, available body text, publication timestamps and rubric version. Unchanged content retains its assessment; changed evidence marks the old assessment STALE.

A bounded background queue sends MEME_ANALYSIS jobs only to workers advertising `meme_analysis_v1`. Queued analyses are now claimed oldest-first, preventing continuously arriving high-prefilter stories from starving older queued work. Video and political-analysis jobs normally take precedence, but after two consecutive higher-priority claims (configurable), one eligible meme job receives a fairness turn. This cannot override worker capabilities, idle availability or lease safeguards, and a busy worker may still take significant time to clear the backlog. Once a job is claimed, the worker may enrich the original source using the existing fetcher and sends the result with its candidate. Stale tokens or source/fingerprint mismatches are rejected.

The evaluator uses the configured local Ollama server through the existing `OllamaClient` and shared `output/runtime/ollama.lock`. The default model is `llama3.1:8b`. It receives a bounded article evidence list, treating RSS text as data and ignoring embedded instructions. It assigns eight 0–10 dimensions; Java calculates the weighted score. No network or third-party model is needed for text evaluation beyond the configured Ollama service.

| Dimension | Maximum |
|---|---:|
| First-second hook | 20 |
| Absurdity / contradiction | 20 |
| Visual meme potential | 15 |
| Relatability | 10 |
| Originality | 15 |
| Factual clarity | 10 |
| Timeliness | 5 |
| Engagement potential | 5 |

The numeric score runs 0–100. With default thresholds: 85+ EXCELLENT, 72+ WORTHY, 55+ POSSIBLE, and below 55 LOW. A separate confidence number reflects the clarity of provided evidence; it is not a truth probability. A single attributed publisher is sufficient for evaluation when article description/body text supports the premise. Headline-only content is labeled **INSUFFICIENT_EVIDENCE** with no numerical meme score, not mislabeled as unfunny.

Suggested angles include a short hook, visual concept and supported F-number reference to source article text. Unknown fact references, out-of-range dimensions and unsupported direct quotations are rejected. The engine flags possible sensitive editorial problems and caps victim-centered tragedy scores at 30. These checks are helpful screening, not a substitute for editorial review; a human should assess humor, context and any disputed allegations before publishing.

## Configuration

Configure in `defaults.txt` or your loaded configuration:

| Key | Default | Purpose |
|---|---|---|
| `memeWorthinessEnabled` | `true` | Enable new evaluator/worker capabilities |
| `memeWorthinessAutoAnalyze` | `true` | Automatically queue fresh eligible story analyses |
| `memeWorthinessModel` | `llama3.1:8b` | Worker-local Ollama model |
| `memeWorthinessOllamaUrl` | inherits `ollamaUrl` | Optional alternate local Ollama endpoint |
| `memeWorthinessTemperature` | `0.2` | Structured scoring temperature |
| `memeWorthinessThreshold` | `72` | Minimum score for the WORTHY category; no longer hides completed analyses from discovery |
| `memeWorthinessExcellentThreshold` | `85` | EXCELLENT classification threshold |
| `memeWorthinessPrefilterMinimum` | `35` | Priority heuristic before bounded sample |
| `memeWorthinessMaxAnalysisPerScan` | `40` | Maximum new evaluation jobs per completed scan (not a guaranteed Ollama throughput) |
| `memeWorthinessMaxPending` | `60` | Limit on queued + active meme evaluation jobs |
| `memeWorthinessRecentLookbackHours` | `8` | Prioritize FOUND stories published within the recent window (1–168 hours) |
| `memeWorthinessRecentSharePercent` | `90` | Reserve capacity for recent candidates while still sampling older backlog (0–100) |
| `memeWorthinessExplorationPercent` | `25` | Reserve slots for low-preliminary-score candidates in each age pool (0–100) |
| `memeWorthinessOtherJobsPerAnalysis` | `2` | Permit one meme analysis claim after this many higher-priority video/political claims (1–20) |
| `memeWorthinessMaxRetries` | `2` | Retry limit following transient model/worker failures |
| `commandCenterAutoMeme` | `false` | Reserved switch; automatic meme video production is not enabled by this feature |

Disable only background discovery with `memeWorthinessAutoAnalyze=false`; manual REANALYZE MEME remains available when the engine is enabled. Turn off `memeWorthinessEnabled` to disable evaluation entirely; existing standard and meme video production are separate.

## Queue, persistence and API

The controller stores independent namespaced state in `data/command_center_state.json`, including `memeAnalysisStatus`, `memeAnalysisInputHash`, `memeScore`, `memeConfidence`, `memeClassification`, `memeAnalysis`, `memeManualDecision`, `memeAnalysisAttempts` and worker lease identity. The six workflow states are NOT_SCORED, QUEUED, ANALYZING, COMPLETE, FAILED and STALE. Claim IDs and input fingerprints guard against late callbacks; duplicate completion acknowledgments from the same claim are idempotent. Worker heartbeat renews the analysis lease. Expired leases retry up to the configured cap; failure never changes video production status.

Authenticated controller operations:

- `POST /api/stories/{id}/analyze-meme`: enqueue analysis
- `POST /api/stories/{id}/meme-decision`: set `WORTH`, `NOT_WORTH`, or `AUTO`
- `POST /api/jobs/{id}/meme-complete`: commit worker result and prepared evidence
- `POST /api/jobs/{id}/meme-fail`: report a failed analysis
- `GET /api/board/stories?status=MEME_DISCOVERY&memeFilter=ALL_ANALYZED`: paginated source-story evaluations (new default discovery UI)
- `GET /api/board/stories?status=MEME_WORTHY`: legacy qualifying-only query remains supported
- `GET /api/state`: independent meme assessment counts, `memeRecentLookbackHours` and current-window `memeRecentFound`, `memeRecentAnalyzed`, `memeRecentQueued`, `memeRecentUnscored`, `memeRecentFailed`, `memeRecentWorthy` counters

In MEME DISCOVERY, a compact **8H FOUND / ANALYZED / PENDING / UNSCORED / FAILED / QUALIFIED** funnel reveals which portion of the recent discovery pool has actually reached Ollama. Display age/search filters do not control scheduling; the lookback and quotas are configured independently. All counts come from current controller state, including lightweight `/api/state?includeStories=false` snapshots. "QUALIFIED" includes manual WORTH decisions; "ANALYZED" requires a complete, non-stale assessment.

Each completed evaluation appears once in MEME DISCOVERY, **even when the score is below 72 or manually rejected**. Select ALL ANALYZED (default), EXCELLENT (85+), WORTHY (72+), POSSIBLE (55–71), LOW (0–54), INSUFFICIENT EVIDENCE, QUEUED / ANALYZING, or FAILED ANALYSES. Only completed assessments appear in ALL ANALYZED; pending and failed work have separate filters. The displayed thresholds use the active configuration. Nothing is automatically approved for meme production by appearing here. A missing score is sorted after scored stories; stale assessments do not masquerade as completed results. The rail count is the number of completed evaluations within the age filter, not just above-threshold hits.

Every story displays once regardless of whether original, narrative review and meme video versions exist. The view sorts by meme score descending with stable story-ID ties, combines with the existing age and search controls, and supports MAKE MEME, REANALYZE MEME, MEME WORTH IT, NOT MEME WORTHY and AUTO. Each result shows all eight underlying Ollama dimensions and supporting reasons. A manual meme decision **never** changes ordinary WORTHY state. The MAKE MEME action uses the existing MEME_RAGEBAIT production queue, evidence builder, ComfyUI, TTS and video archive; scoring alone **never** queues any video. Production-time factual guards remain authoritative.

## Validation and operational limits

Run `bash build_linux.sh`, `java -cp build/classes autonewsroller.Main --self-test` and `npm run test:browser`, plus the existing Windows/Linux, visual and worker CI suites. Mock Ollama tests verify result shape without requiring a GPU. A real Windows Ollama and ComfyUI worker test is still needed to judge actual model output quality, first-second hooks, visual humor and practical processing times.

Prefilter scoring is heuristic and cannot perfectly recognize emerging meme formats. A high scoring story may still be unfunny, tasteless, misleading, or inappropriate for publication; confidence and editorial warnings should be considered separately. Attributable RSS reporting is not the same as independent fact verification. The final meme script generator remains responsible for validating its actual narration against source evidence.

## Practical interpretation of low meme-worthy counts

A large FOUND count and a small MEME WORTHY count **does not** mean Ollama rejected every discovery. Compare `memeRecentAnalyzed` to `memeRecentFound` first. The maximum pending queue and worker processing speed constrain actual evaluations; increasing the per-scan budget changes only how many jobs may be queued. The viewer's AGE filter changes the displayed stories, not the worker scheduler. The minimum meme-worthy score remains 72 by default; this change does not weaken the content or source-evidence safeguards and does not auto-create meme videos. User-specific configuration overrides may still retain old per-scan limits after updating `defaults.txt`.
