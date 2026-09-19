# Configuration Reference

## Files

Primary configuration lives in:

- defaults.txt
- config/sources.json
- config/categories.json
- config/ranking.json

Source URLs are not hard-coded in the core ingestion loop.

## defaults.txt

Current defaults:

    newsMaxAgeHours=24
    minimumIndependentSources=2
    targetDurationSeconds=60
    ollamaModel=llama3.1:8b
    ollamaUrl=http://127.0.0.1:11434/api/generate
    ollamaRetries=3
    ollamaKeepAlive=30m
    ttsPrimary=kokoro
    ttsFallback=qwen3
    kokoroVoices=af_heart,af_bella,af_nicole,bf_emma
    qwenVoices=Ryan,Aiden,Ono_Anna,Sohee
    qwenUrl=http://127.0.0.1:8765
    comfyUrl=http://127.0.0.1:8188
    imageCheckpoint=
    comfyAutoPickCheckpoint=true
    videoEncoder=auto
    videoWidth=1080
    videoHeight=1920
    videoFps=30
    captions=sentence
    workers=4
    feedFetchTimeout=12
    articleFetchTimeout=30
    articleEnrichmentEnabled=false
    feedRefreshMinutes=30
    ffmpegCommand=ffmpeg
    ffprobeCommand=ffprobe
    userAgent=AutoNewsRoller/0.1 (+https://github.com/tndmadman/AutoNewsRoller)
    httpRetries=2
    imageWidth=768
    imageHeight=1344
    imageSteps=24
    imageCfg=5.0
    imageNegative=text, watermark, logo, captions, low quality, distorted, deformed
    commandCenterHost=127.0.0.1
    commandCenterPort=8787
    commandCenterScanMinutes=10
    commandCenterAutoQueue=true
    commandCenterAutoThreshold=0.68
    commandCenterMaxQueued=12
    commandCenterUseComfy=true
    commandCenterControllerUrl=http://127.0.0.1:8787

## News settings

### newsMaxAgeHours

Default: 24

Articles older than this are discarded from normal discovery unless a CLI override is supplied.

CLI override:

    --max-age-hours N

### minimumIndependentSources

Default: 2

A cluster normally needs at least this many independent sources.

CLI override:

    --minimum-independent-sources N

An authoritativePrimary source can bypass the minimum, with that exception recorded in the FactPackage.

### targetDurationSeconds

Default: 60

Used as the target duration supplied to the script generator.

CLI override:

    --duration N

This is a target, not an exact hard duration. Final video duration follows the narration audio.

## Ollama settings

### ollamaModel

Default: llama3.1:8b

This is the model sent to the local Ollama generate API.

### ollamaUrl

Default:

    http://127.0.0.1:11434/api/generate

### ollamaRetries

Default: 3

NewsScriptGenerator retries model generation/validation this many times.

### ollamaKeepAlive

Default: 30m

Passed as keep_alive to Ollama.

Launch flags can override the behavior:

    --keep-ollama-loaded
    --unload-ollama-after

The first sets the keep-alive value to 30m for the current run. The second sets it to 0.

## TTS settings

### ttsPrimary and ttsFallback

Current values describe the intended configuration:

    ttsPrimary=kokoro
    ttsFallback=qwen3

Important: the live NewsPipeline code currently explicitly attempts Kokoro first and Qwen second. Changing these two text values alone does not currently swap the Java execution order.

### kokoroVoices

Default pool:

- af_heart
- af_bella
- af_nicole
- bf_emma

A stable voice is chosen for a video based on the story ID hash.

### qwenVoices

Default pool:

- Ryan
- Aiden
- Ono_Anna
- Sohee

A stable fallback voice is likewise chosen from the story ID.

### qwenUrl

Default:

    http://127.0.0.1:8765

The helper is bound to localhost by default.

## ComfyUI settings

### comfyUrl

Default:

    http://127.0.0.1:8188

### imageCheckpoint

Default: blank

Set this to the exact checkpoint name returned by ComfyUI's CheckpointLoaderSimple object info.

Example:

    imageCheckpoint=RealVisXL_V5.0_fp32.safetensors

The pipeline checks that a configured checkpoint exists before it submits generation.

### comfyAutoPickCheckpoint

Default: true

When false and imageCheckpoint is blank, optional ComfyUI generation fails over to procedural cards.

When true, the first discovered compatible checkpoint may be selected.

For predictable production, explicit imageCheckpoint is preferable.

### imageWidth / imageHeight

Defaults:

- width 768
- height 1344

These are the optional ComfyUI generation dimensions, not the final video dimensions.

### imageSteps

Default: 24

### imageCfg

Default: 5.0

### imageNegative

Default negative-prompt text:

    text, watermark, logo, captions, low quality, distorted, deformed

The ComfyUI code adds an editorial-news prompt qualifier and explicitly asks for no text or logos.

## Video settings

### videoEncoder

Default: auto

Accepted normalized modes:

- auto
- nvenc
- x264

Aliases such as nvidia/gpu and cpu/libx264 normalize to the corresponding mode.

### videoWidth / videoHeight

Defaults:

- 1080
- 1920

Vertical 9:16 output.

### videoFps

Default: 30

### captions

Default: sentence

Current modes:

- off
- sentence
- word

Sentence and word modes are duration-proportional caption timing generated from narration text.

Kokoro can also write an exact token-timing sidecar when the underlying Kokoro result exposes timestamps, but VideoRenderer currently uses CaptionWriter rather than consuming that Kokoro timing file.

## Worker settings

### workers

Default: 4

Used when --workers is not supplied.

Effective worker count can be lower when fewer verified candidates exist.

The worker number is whole-video concurrency. Ollama and GPU-heavy operations have their own serialization/resource controls.

## Command Center settings

### commandCenterHost

Default:

    127.0.0.1

Bind address for --command-center mode.

Use 0.0.0.0 only when the controller should accept LAN connections. Configure AUTONEWS_TOKEN when listening beyond localhost.

### commandCenterPort

Default:

    8787

HTTP dashboard/API port.

### commandCenterScanMinutes

Default:

    10

Full RSS scan interval used by the Command Center scheduler.

### commandCenterAutoQueue

Default:

    true

Allows verified stories meeting the automatic worthiness threshold to enter the production queue without manual approval.

### commandCenterAutoThreshold

Default:

    0.68

Minimum StoryRanker score for AUTO decisions to queue a verified story.

This is a production-priority threshold, not a probability that the story is true.

### commandCenterMaxQueued

Default:

    12

Maximum QUEUED + PRODUCING depth used by automatic queueing. Manual MAKE decisions can still be applied to verified stories.

### commandCenterUseComfy

Default:

    true

Controls whether remote Command Center jobs ask the production worker to use optional ComfyUI imagery.

### commandCenterControllerUrl

Default:

    http://127.0.0.1:8787

Default controller URL used by --worker mode.

### AUTONEWS_TOKEN

Not stored in defaults.txt.

Optional environment variable shared by the controller and workers.

When configured, API/video endpoints require the token.

A CLI --token value can also be supplied, but environment variables are preferable to leaving long-lived tokens in shell history or service command lines.

## config/source_bias.json

Optional metadata for the dashboard political source-mix visualization.

The repository ships with provider=unconfigured and no labels.

AutoNewsRoller does not infer political classifications itself. See docs/COMMAND_CENTER.md.

## HTTP settings

### feedFetchTimeout

Default: 12 seconds

Used for RSS/Atom feed requests. The shorter feed-specific timeout prevents a dead endpoint from stalling an 88-feed scan for too long.

### articleFetchTimeout

Default: 30 seconds

Used only for optional linked-article extraction.

### articleEnrichmentEnabled

Default: false

With the expanded feed pack, discovery is RSS-only by default. Linked article HTML is not fetched unless this is explicitly enabled.

### feedRefreshMinutes

Default: 30

Legacy/general refresh setting retained for non-Command-Center workflows.

The Command Center uses commandCenterScanMinutes. watch_news_windows.bat now launches the Command Center and passes its first argument as that controller scan interval.

### userAgent

Default:

    AutoNewsRoller/0.1 (+https://github.com/tndmadman/AutoNewsRoller)

This intentionally identifies the project rather than pretending to be a normal browser.

### httpRetries

Default: 2

RssSource now uses this value as its maximum feed-request attempt count. ArticleFetcher retains its own bounded retry behavior.

## FFmpeg settings

### ffmpegCommand

Default:

    ffmpeg

### ffprobeCommand

Default:

    ffprobe

Change these if the binaries are not on PATH and an explicit executable path is preferred.

## config/sources.json

The default pack now contains **88 enabled RSS feeds**. Multiple section feeds from the same publisher deliberately use the same `name` so they do not count as separate independent publishers during verification.

Publisher groups currently include:

- BBC News
- The Guardian
- CBS News
- Fox News
- ABC News
- NPR
- The New York Times
- Global News
- NASA
- ScienceDaily
- Ars Technica
- TechCrunch
- The Verge
- WIRED
- Engadget
- Phys.org
- Space.com
- Tom's Hardware
- ESPN
- CNBC
- MarketWatch

The exact feed URLs and categories are maintained in `config/sources.json`, which is the source of truth.

NASA's old JPL-specific feed was removed from the default pack after live testing showed a Java TLS trust failure followed by an HTTP 403 on the fallback request. The replacement NASA feeds are the main NASA WordPress RSS endpoints, including general content, news releases, technology, and aeronautics.

## Source object fields

Example:

    {
      "name": "Example Publisher",
      "type": "rss",
      "category": "technology",
      "url": "https://example.com/feed.xml",
      "enabled": true,
      "trustTier": 2,
      "authoritativePrimary": false
    }

### name

Human-readable publisher label used in source metadata and source-label validation.

### type

Current live ingestion code only processes sources whose type is rss.

Atom is supported as an XML feed format through the RSS source parser even though the source type value is still rss.

### category

Used by CLI category filtering.

A requested category of general acts as the broad/default behavior rather than limiting to a source literally tagged general.

### url

Feed URL.

### enabled

Disabled entries are skipped.

### trustTier

Lower values are treated as higher quality by ranking/verification confidence calculations.

Current built-in values use tier 1 for the NASA primary source and tier 2 for the publisher feeds.

This is an internal heuristic, not an externally audited media-quality rating.

### authoritativePrimary

When true, that source can satisfy verification without the usual number of independent publishers.

Use sparingly for genuine first-party/authoritative sources.

## config/categories.json

Current labels:

- general
- world
- technology
- science
- business
- markets
- environment
- health
- entertainment
- gaming
- local
- custom

The list is descriptive. Source category values can be customized.

## config/ranking.json

Current weights:

    {
      "freshness": 0.30,
      "sourceCount": 0.20,
      "sourceQuality": 0.20,
      "novelty": 0.20,
      "videoSuitability": 0.10
    }

The current code passes novelty=1 during discovery, so novelty weighting is effectively a constant contribution among candidates that survived history filtering. Dynamic novelty scoring remains a future enhancement.

## CLI flags currently handled by Main

- --self-test
- --dry-run
- --batch-target N
- --workers N
- --category NAME
- --max-age-hours N
- --minimum-independent-sources N
- --duration N
- --encoder auto|nvenc|x264
- --comfyui
- --fixture
- --batch-dir PATH
- --keep-ollama-loaded
- --unload-ollama-after

Unknown flags without recognized handling are not used by Main.

## Configuration caveat

Not every value in defaults.txt is yet a fully dynamic runtime switch.

Documented examples:

- ttsPrimary/ttsFallback do not currently reorder the Java TTS attempts.
- httpRetries does not currently override the hard-coded three-attempt fetch loops.
- feedRefreshMinutes is not currently read by watch_news_windows.bat.

These are good cleanup targets because configuration should ideally be authoritative rather than partly descriptive.
