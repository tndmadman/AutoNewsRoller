# Output, Audit, Provenance, and History

## Purpose

AutoNewsRoller writes intermediate artifacts rather than only a final MP4.

This makes it possible to answer:

- what story was selected;
- which source articles supported it;
- what facts were extracted;
- what script was produced;
- which visual plan was used;
- which TTS engine actually generated narration;
- which video encoder actually rendered the final output;
- whether a video passed audit;
- whether the same story has already been generated.

## Batch directory

Normal runs create a timestamped directory under output.

Typical structure:

    output/
      batch_YYYYMMDD_HHMMSS/
        debug.log
        runtime/
          events.jsonl
          worker_001.log
          worker_002.log
          ...
        slot_001/
          story.json
          articles/
            00.json
            01.json
            ...
          fact_package.json
          script.json
          visual_plan.json
          narration/
            narration.wav
            narration.wav.txt
            narration.wav.json
            optional narration.timing.tsv
          visuals/
            ...
          render/
            video.mp4
            video.mp4.concat.txt
            video.mp4.srt
          audit.json
        final_videos/
          sanitized_story_name.mp4
          sanitized_story_name.mp4.json

Exact files can vary depending on dry-run mode, failures, and optional components.

## debug.log

Batch-level diagnostic log.

Examples of entries:

- discovery start and end;
- feed failures;
- article extraction failures;
- rejected verification clusters;
- ComfyUI fallback reasons.

It is append-only for the batch.

## Worker logs

Path:

    runtime\worker_###.log

Contains slot-level worker events such as:

- story started;
- TTS engine actually used;
- Kokoro failure reason;
- ComfyUI generation success/failure;
- final approved output;
- rejection reason.

These are especially useful when several whole-video workers are active.

## events.jsonl

Structured dashboard state.

Each line is a serialized WorkerState with fields such as:

- worker;
- slot;
- stage;
- detail;
- timestamp.

Pipeline stages are used by tools\batch_dashboard.ps1 to display current slot state.

This file is append-only.

## story.json

Serializes the selected StoryCluster.

Contains the cluster identity, topic, article membership, entities, and fingerprint data needed to understand why those articles were grouped.

## articles directory

Stores each Article object used in the cluster.

Article metadata includes values such as:

- ID;
- publisher;
- title;
- URL;
- canonical URL;
- publication timestamp;
- discovery timestamp;
- author;
- category;
- feed description;
- extracted body text where available;
- language;
- source trust tier;
- authoritative-primary flag.

This is the evidence snapshot used by later steps.

## fact_package.json

This is the factual grounding object supplied to script generation.

Fields include:

- storyId;
- headline;
- summary;
- facts;
- disputedClaims;
- sources;
- sourceCount;
- independentSourceCount;
- confidence;
- authoritativePrimaryAccepted.

Each FactClaim can include:

- statement;
- supporting source URLs;
- confidence;
- contested flag.

Current limitation:

The present verification implementation initializes disputedClaims as empty. Explicit contradiction extraction remains future work.

## script.json

Contains the generated NewsScript.

Expected content includes:

- story ID;
- headline;
- narration;
- segments;
- estimated duration;
- source labels.

Each segment includes:

- index;
- narration;
- purpose;
- visual type;
- visual prompt;
- duration target.

Dry-run mode writes a deterministic script instead of invoking Ollama.

## visual_plan.json

Contains the planned visual sequence.

Current planner generally creates:

- headline card;
- up to five content visuals;
- source card.

The plan exists before actual rendering so visual intent can be inspected separately from image generation.

## Narration artifacts

Typical narration path:

    slot_...\narration\narration.wav

Associated text:

    narration.wav.txt

Metadata:

    narration.wav.json

The metadata identifies:

- engine;
- voice;
- WAV path.

Possible engine values in current pipeline:

- Kokoro
- Qwen3 fallback

Kokoro can additionally write:

    narration.timing.tsv

when exact token timing is available.

## Procedural/generated visual provenance

The final MP4 sidecar includes imageSources.

Procedural card entries include information such as:

- type=procedural-card;
- rendered path;
- visual type.

A ComfyUI-generated image entry includes information such as:

- type=comfyui-generated;
- path;
- checkpoint;
- prompt.

This makes generated imagery distinguishable from deterministic cards.

## render directory

The working render is created before final approval.

Typical files:

- video.mp4
- video.mp4.concat.txt
- video.mp4.srt

The working MP4 is audited before it is copied into final_videos.

## audit.json

VideoAudit starts with status=rejected and changes to approved only after checks pass.

Current checks:

1. video exists;
2. video size is nontrivial;
3. narration audio exists;
4. audio size is nontrivial;
5. FFmpeg volumedetect does not report silence;
6. video resolution matches configured width and height;
7. video duration is positive.

NewsPipeline then adds additional audit metadata:

- sourceCount;
- independentSources;
- verifiedFacts;
- contestedFacts;
- ttsEngine;
- encoder;
- duplicateStoryFingerprint=false.

## Final filename handling

Final filenames are generated from the story topic and sanitized.

If a file already exists, FileNames.unique creates a collision-safe alternative rather than overwriting the previous final output.

Self-test verifies this behavior.

## Final MP4 sidecar

For each approved final MP4:

    story_name.mp4
    story_name.mp4.json

The sidecar is the main provenance record.

Current fields written by NewsPipeline include:

- storyId;
- storyFingerprint;
- script;
- generatedTimestamp;
- sources;
- factPackageHash;
- scriptHash;
- ttsEngineActuallyUsed;
- voice;
- imageSources;
- comfyCheckpoint;
- videoEncoderRequested;
- videoEncoderActuallyUsed;
- ffmpegVersion;
- ffmpegCommand;
- ollamaModel;
- autoNewsRollerCommit;
- output.

## Hashes

The project computes SHA-256 hashes for:

- fact package;
- script;
- story fingerprint basis.

The hashes help identify whether factual/script content changed without storing a separate content-addressed object store.

## Commit provenance

The final sidecar attempts to resolve the AutoNewsRoller commit.

Resolution order:

1. GITHUB_SHA environment variable;
2. git rev-parse HEAD;
3. "unknown" if neither can be resolved.

This makes CI-produced and normal git-checkout output traceable to code state when possible.

## Persistent data files

### data\feed_cache.json

Stores ETag/Last-Modified values per feed.

Used to make conditional feed requests.

### data\seen_articles.jsonl

Append-only Article history keyed by article ID.

ArticleHistory loads IDs and avoids appending an exact already-seen article ID again during discovery.

Important distinction:

seen_articles tracks discovered article identity. It is not the main story-level duplicate block.

### data\story_history.jsonl

Semantic story history.

Each generated story writes values including:

- storyId;
- normalizedHeadline;
- fingerprint;
- entities;
- category;
- sourceUrls;
- publishers;
- generatedVideo;
- generationTime.

Before candidates are ranked for production, NewsPipeline checks whether the cluster fingerprint is already present here.

### data\publish_history.jsonl

Separate append-only final-output record.

Each row includes:

- storyId;
- video;
- fingerprint;
- publishedAt.

Despite the field name, this currently means approved/generated output history inside AutoNewsRoller. It does not prove the MP4 was uploaded to an external social platform.

## Duplicate protection model

There are several layers:

1. article ID from publisher name + canonical URL;
2. seen article history;
3. clustering of same-event articles;
4. semantic story fingerprint;
5. story_history lookup;
6. collision-safe output filename.

This is useful but not perfect.

Potential duplicate cases still possible:

- changed headlines produce a new fingerprint;
- an ongoing story develops materially and gets a different entity set;
- two semantically identical events cluster separately due to similarity threshold;
- source URLs contain tracking/query variations not fully normalized.

Future duplicate protection should compare recent StoryHistory entries using semantic similarity rather than only exact fingerprint equality.

## Audit philosophy

An "approved" MP4 means the current automated checks passed.

It does not mean:

- the story is guaranteed factually correct;
- every source has been legally cleared for every use;
- the script has been human-reviewed;
- the visuals are free of misleading implications;
- the external publishing platform will accept/monetize it.

For unattended production, add higher-level editorial/content-policy review checks in addition to technical media audit.

## Recommended retention

For troubleshooting and accountability, retain at least:

- final MP4;
- final MP4 sidecar;
- fact_package.json;
- script.json;
- audit.json;
- worker/debug logs for failed runs.

If storage becomes large, intermediate procedural images and working render files can be pruned after a configurable retention period, but provenance should remain.
