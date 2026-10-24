# Independent meme-worthiness engine

The **MEME DISCOVERY** Command Center rail is an editorial discovery view, not a news truth score and not a prediction of virality. This engine operates independently of the existing StoryRanker, WORTHY decisions, political framing analysis, and the MEME_RAGEBAIT video pipeline.

## Discovery and evaluation

On each completed RSS scan the controller computes a cheap deterministic priority score from the story title and available article descriptions. This **prefilter is not the final judgment**: some lower-ranked candidates are also selected within the budget. Each stored candidate is fingerprinted from its identity, attributable source URLs, article titles, descriptions, available body text, publication timestamps and the rubric version. Identical content reuses an existing complete assessment; changed inputs mark older results STALE.

A bounded background queue sends MEME_ANALYSIS jobs only to workers advertising `meme_analysis_v1`. Active video jobs always have claim priority. Once a job is claimed, the worker may enrich the selected article using the existing article fetcher, not a different outlet, and sends the analyzed candidate and result in one transaction. The controller compares the enriched article's identity and pre-existing content to its claimed source snapshot. Results with stale claim tokens, changed source identities or mismatched fingerprints are rejected.

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
| `memeWorthinessMaxAnalysisPerScan` | `20` | Maximum newly queued evaluations per completed scan |
| `memeWorthinessMaxPending` | `60` | Limit on queued + active meme evaluation jobs |
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
- `GET /api/state`: independent meme assessment counts

Each completed evaluation appears once in MEME DISCOVERY, **even when the score is below 72 or manually rejected**. Select ALL ANALYZED (default), EXCELLENT (85+), WORTHY (72+), POSSIBLE (55–71), LOW (0–54), INSUFFICIENT EVIDENCE, QUEUED / ANALYZING, or FAILED ANALYSES. Only completed assessments appear in ALL ANALYZED; pending and failed work have separate filters. The displayed thresholds use the active configuration. Nothing is automatically approved for meme production by appearing here. A missing score is sorted after scored stories; stale assessments do not masquerade as completed results. The rail count is the number of completed evaluations within the age filter, not just above-threshold hits.

Every story displays once regardless of whether original, narrative review and meme video versions exist. The view sorts by meme score descending with stable story-ID ties, combines with the existing age and search controls, and supports MAKE MEME, REANALYZE MEME, MEME WORTH IT, NOT MEME WORTHY and AUTO. Each result shows all eight underlying Ollama dimensions and supporting reasons. A manual meme decision **never** changes ordinary WORTHY state. The MAKE MEME action uses the existing MEME_RAGEBAIT production queue, evidence builder, ComfyUI, TTS and video archive; scoring alone **never** queues any video. Production-time factual guards remain authoritative.

## Validation and operational limits

Run `bash build_linux.sh`, `java -cp build/classes autonewsroller.Main --self-test` and `npm run test:browser`, plus the existing Windows/Linux, visual and worker CI suites. Mock Ollama tests verify result shape without requiring a GPU. A real Windows Ollama and ComfyUI worker test is still needed to judge actual model output quality, first-second hooks, visual humor and practical processing times.

Prefilter scoring is heuristic and cannot perfectly recognize emerging meme formats. A high scoring story may still be unfunny, tasteless, misleading, or inappropriate for publication; confidence and editorial warnings should be considered separately. Attributable RSS reporting is not the same as independent fact verification. The final meme script generator remains responsible for validating its actual narration against source evidence.
