# Architecture and Data Flow

## High-level architecture

AutoNewsRoller is primarily a Java orchestration application with Python helpers for model-specific TTS work and PowerShell/batch launchers for Windows operation.

Major Java packages under src/autonewsroller:

- config — defaults and source configuration.
- ingest — feed discovery, conditional HTTP polling, linked-article fetching, article text extraction.
- cluster — similarity scoring and same-event clustering.
- verify — independent-source verification and fact extraction.
- rank — candidate ranking.
- model — Article, StoryCluster, FactClaim, FactPackage, NewsScript, and VisualPlan records/models.
- script — Ollama client, prompt construction, JSON parsing, and script validation.
- tts — Kokoro/Qwen runners and fallback abstraction.
- gpu — cross-process GPU lane.
- visuals — visual planning, procedural card rendering, optional ComfyUI generation.
- video — captions, encoder probing, FFmpeg rendering.
- audit — source/video audit data.
- history — seen-article, story, and publish histories.
- orchestration — batch coordination, pipeline execution, events, logs, owned-process cleanup.
- util — JSON, hashing, filenames, and text helpers.

Python helpers under tools:

- kokoro_tts.py
- qwen3_tts.py
- qwen3_tts_server.py
- fetch_news.py
- article_extract.py

Windows operational helpers:

- setup_windows.bat
- build_windows.bat
- run_news_video_windows.bat
- batch_create_news_videos_windows.bat
- watch_news_windows.bat
- tools/batch_dashboard.ps1

## Program entry

The Java entry point is autonewsroller.Main.

Main performs the following steps:

1. Resolve the repository root from the current working directory.
2. Run SelfTest and exit if --self-test is supplied.
3. Load defaults.txt and ranking.json.
4. Register a shutdown hook that only attempts to stop a Qwen helper process AutoNewsRoller can prove it owns.
5. Parse CLI options.
6. Resolve target count, worker count, category, max age, minimum sources, duration, encoder, ComfyUI flag, dry-run mode, fixture mode, and batch directory.
7. Construct NewsPipeline.
8. Run BatchCoordinator.
9. Print approved/target/attempt counts and rejection reasons.
10. Exit nonzero if the requested approval target was not reached.

## Discovery

NewsPipeline.discover drives live discovery.

For each source loaded from config/sources.json:

1. Skip disabled sources.
2. Skip non-RSS source types in the current implementation.
3. Apply category filtering.
4. Construct RssSource.
5. Fetch the feed.
6. Parse RSS item entries or Atom entry entries.
7. Reject stories older than maxAgeHours.
8. Optionally fetch the linked article if the feed description is short.
9. Add normalized Article objects to the current discovery set.
10. Append newly observed article IDs to seen_articles.jsonl.

A failed feed is logged and skipped instead of terminating the entire discovery run.

## Feed fetching and caching

RssSource uses java.net.http.HttpClient.

Request characteristics:

- explicit User-Agent;
- Accept header for RSS, Atom, and XML;
- connection/request timeout;
- up to three attempts in the current RssSource implementation;
- exponential-ish short backoff between attempts;
- retries for transient 429 and 5xx behavior;
- ETag and Last-Modified conditional requests.

FeedCache stores server cache metadata in data/feed_cache.json.

On later requests:

- If an ETag exists, AutoNewsRoller sends If-None-Match.
- If Last-Modified exists, it sends If-Modified-Since.
- HTTP 304 returns no new feed entries for that source and avoids reprocessing the payload.

This is standard aggregator/feed-reader behavior and reduces unnecessary traffic.

## RSS and Atom parsing

The parser supports:

- RSS item elements;
- Atom entry elements;
- title;
- link;
- description/summary/content;
- author/creator;
- pubDate/published/updated.

URLs are canonicalized by stripping fragments while preserving scheme, authority, path, and query.

Each Article receives a deterministic ID derived from source name plus canonical URL.

The XML parser attempts to disable dangerous external entity behavior and disallows DOCTYPE declarations where supported.

Malformed feed XML throws for that feed, which the calling pipeline catches and logs.

## Linked-article extraction

If live discovery is enabled and a feed description is shorter than 120 characters, AutoNewsRoller may fetch the linked article URL.

ArticleFetcher:

- follows normal redirects;
- sends the configured User-Agent;
- requests HTML/XHTML;
- uses a timeout;
- retries up to three times;
- retries 429 and server-side failures;
- throws on non-retryable failure.

ArticleParser performs dependency-free HTML-to-text extraction.

It:

- ignores script and style content;
- adds boundaries around common paragraph/list/header tags;
- normalizes whitespace;
- drops very short lines;
- drops lines matching cookie, privacy policy, sign-up, subscribe, or advertisement text.

This is a conservative extractor, not a full readability engine. Complex sites, client-rendered pages, paywalls, anti-bot challenges, and unusual markup may produce poor extraction or no useful body text.

## Story clustering

StoryClusterer uses SimilarityScorer and a default threshold of 0.52.

For each article:

1. Compare it against existing groups.
2. Find the highest similarity against any article in each group.
3. Add it to the best group if the best score meets the threshold.
4. Otherwise create a new group.

Within each cluster:

- articles are sorted by publication time;
- the newest headline becomes the topic;
- simple entities are collected from titles;
- a fingerprint is created from normalized topic plus sorted entities;
- the SHA-256 fingerprint is also used to derive a short story ID.

The clustering algorithm is deterministic for a given ordered input but is intentionally simple. It should be tuned against live-news false-positive and false-negative examples.

## Duplicate-story control

Before a verified cluster is offered for production, NewsPipeline checks data/story_history.jsonl.

If its fingerprint has already been generated, the cluster is skipped.

This prevents exact semantic fingerprints already recorded as produced stories from being re-created in later polling cycles.

Important limitation: the fingerprint is based on normalized topic and extracted entities. A substantially rewritten headline or changed entity set may produce a different fingerprint even if humans consider it the same ongoing story.

## Independent-source verification

SourceVerifier first reduces the cluster to a set of independent articles.

An article is not counted as independent if:

- another accepted article came from the same publisher; or
- its normalized description/title is more than 0.90 Jaccard-similar to an already accepted article.

The cluster is accepted when either:

- independent source count is at least the configured minimum; or
- any article is marked authoritativePrimary.

Default minimum: 2 independent sources.

The FactPackage records:

- story ID;
- headline;
- summary;
- fact claims;
- disputed claims list;
- source metadata;
- total source count;
- independent source count;
- confidence score;
- whether an authoritative-primary exception was used.

The current implementation initializes disputedClaims as an empty list. Explicit contradiction detection is therefore an area for future improvement.

## Fact extraction

FactExtractor currently extracts candidate claims from:

- each article headline;
- sentences in each article description.

Claims are normalized and grouped.

A FactClaim records supporting source URLs and a confidence that rises with the number of supporting URLs.

Current limitation: bodyText is gathered for short-feed entries but FactExtractor currently focuses on headline and description text. Richer evidence extraction from the article body is a future improvement.

## Ranking

StoryRanker uses weights from config/ranking.json.

Current default weights:

- freshness: 0.30
- sourceCount: 0.20
- sourceQuality: 0.20
- novelty: 0.20
- videoSuitability: 0.10

Freshness declines over roughly a 48-hour scale.

Source-count score saturates at four independent sources.

Source-quality score is derived from trustTier, with lower trustTier values treated as higher quality.

Video suitability currently rises with the number of extracted facts and saturates around eight.

The novelty parameter is currently passed as 1 during discovery, so the configured novelty weight is not yet based on a dynamic semantic novelty calculation. Duplicate history is handled separately.

## Batch coordination

BatchCoordinator receives the ranked candidate list.

It:

- limits effective workers to available candidate count;
- uses a fixed-size ExecutorService;
- assigns entire stories to workers;
- reserves approval capacity before an attempt begins;
- counts attempts separately from approvals;
- logs failures as rejections;
- keeps filling available approval capacity until target is met or candidates are exhausted.

The in-flight reservation exists specifically to avoid a race where a target of one could result in two simultaneous workers both producing approved outputs.

## Script generation

For non-dry runs, NewsPipeline constructs OllamaClient using:

- endpoint from defaults;
- configured model;
- keep-alive value;
- a cross-process lock file at output/runtime/ollama.lock.

OllamaClient holds an exclusive file lock around generation, so multiple video workers do not issue simultaneous Ollama generations through this code path.

It asks Ollama for JSON output and sends the FactPackage as data.

NewsScriptGenerator retries failed generation/validation up to ollamaRetries.

Dry-run mode bypasses Ollama and uses a deterministic script assembled from verified fact claims.

## Visual planning

VisualPlanner creates:

- a headline card;
- up to five segment visuals;
- a source card.

Procedural cards are always available through Java2D CardRenderer.

When ComfyUI is enabled, the pipeline may replace one eligible procedural visual with a generated image. The rest remain deterministic cards.

## Narration

The live narration sequence is hard-coded operationally as:

1. emit "KOKORO active";
2. try Kokoro;
3. on success, emit "KOKORO USED";
4. on failure, log the Kokoro error;
5. emit "QWEN3 FALLBACK active";
6. run Qwen;
7. emit "QWEN3 FALLBACK USED" on success.

This makes the runtime state identify the actual TTS path.

## Video rendering

VideoRenderer:

- probes narration duration with ffprobe;
- allocates visual duration across images;
- builds an FFmpeg concat input;
- writes SRT captions unless captions=off;
- probes encoder support;
- renders 1080x1920 by default;
- uses H.264 plus AAC;
- adds +faststart.

Encoder options:

- auto — real NVENC test, then x264 fallback;
- nvenc — require NVENC or fail;
- x264 — force libx264.

## Final audit

VideoAudit verifies:

- video exists and is nontrivial in size;
- audio exists;
- audio is not silent according to FFmpeg volumedetect;
- video resolution matches expected width/height;
- duration is positive.

Only then is the final file copied into final_videos and history/provenance written.

## Structured runtime state

Each batch writes:

- debug.log;
- runtime/events.jsonl;
- per-worker log files.

EventLog appends JSONL WorkerState objects.

The PowerShell dashboard reads events.jsonl every 750 milliseconds and renders the latest stage per slot plus recent events. It does not modify the generator source or rewrite itself.

## Process ownership

Qwen is a persistent lazy helper service.

When AutoNewsRoller starts it, qwen3_tts.py assigns a random ownership token and records it in output/runtime/qwen3_tts_owner.json.

OwnedProcesses only sends a shutdown request when:

- the ownership marker exists;
- the running Qwen service is reachable;
- the token returned by /health matches the marker.

This is designed to avoid killing an unrelated service that happens to be listening on the same port.
