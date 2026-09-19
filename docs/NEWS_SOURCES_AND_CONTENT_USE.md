# News Sources, Polling, Bot Behavior, and Content Use

## Important scope

This document describes how AutoNewsRoller currently obtains news and identifies practical content-use concerns.

It is not legal advice.

Publisher terms, API terms, feed terms, copyright status, robots policies, and platform rules can change. Review the current terms for any source before relying on it for commercial automated publishing.

## Where news currently comes from

The default source list is config/sources.json.

Current enabled feeds:

- BBC World
- The Guardian World
- The Guardian Technology
- Ars Technica
- NASA JPL News

The current system is not a general web search engine.

It does not currently query Google News, Bing News, Reddit, X, AP, Reuters, CNN, Fox, or a commercial news API unless such a source is added/configured and supported by the ingestion layer.

## How it pulls news

The default discovery path is feed polling.

AutoNewsRoller makes HTTP GET requests to configured RSS/Atom-compatible feed URLs.

It requests content types including:

- application/rss+xml
- application/atom+xml
- application/xml
- text/xml

The feed returns structured entries that normally contain some combination of:

- headline;
- article URL;
- short description/summary;
- author;
- publication time.

RSS/Atom is specifically intended to be consumed by automated feed readers and aggregators.

That does not automatically mean every possible reuse of the content is unrestricted.

## Conditional requests

AutoNewsRoller records:

- ETag
- Last-Modified

When available, later requests include:

- If-None-Match
- If-Modified-Since

A server can respond:

    304 Not Modified

In that case, AutoNewsRoller skips downloading/reprocessing the unchanged feed payload.

This is one of the main reasons RSS polling is a better discovery mechanism than repeatedly scraping full home pages.

## User-Agent

Current default:

    AutoNewsRoller/0.1 (+https://github.com/tndmadman/AutoNewsRoller)

This means the project intentionally looks like an automated client.

It does not try to impersonate Chrome or Firefox.

For a legitimate feed aggregator, being identifiable and rate-conscious is preferable to pretending the traffic is human.

## When it fetches the actual article page

The linked webpage is fetched when:

- this is normal live discovery, not dry-run behavior; and
- the feed description is shorter than approximately 120 characters.

The reason is to enrich feed entries that provide too little text.

This article-page request is different from fetching RSS.

A normal article page may have:

- site-specific Terms of Service;
- robots directives;
- anti-bot systems;
- paywalls;
- JavaScript challenges;
- rate limits;
- licensing restrictions.

Therefore:

- feed polling is normal aggregator behavior;
- linked-page retrieval is web crawling/scraping and should be treated more carefully.

## Does it look like a bot?

Yes, especially when fetching article HTML.

Indicators include:

- explicit AutoNewsRoller User-Agent;
- java.net.http client behavior;
- no browser JavaScript execution;
- no human navigation session;
- deterministic request patterns;
- automated retries.

That is not inherently improper. Many legitimate services are automated clients.

The goal should be polite automation, not hiding that it is automation.

## Current traffic controls

Implemented:

- conditional RSS GETs;
- request timeouts;
- bounded attempts;
- short backoff;
- feed failures are skipped instead of hammered forever;
- article extraction only happens when the feed summary is short.

Not yet implemented as a shared system-wide policy:

- per-domain request queue;
- per-domain minimum delay;
- robots.txt evaluation;
- Retry-After-aware delay scheduling;
- persistent full article-page cache;
- publisher-specific request ceilings;
- adaptive circuit breaker after repeated 403/429 responses.

A per-domain limiter is one of the strongest recommended hardening improvements before heavy unattended crawling.

## Recommended polite-crawler behavior

For linked article pages, a future implementation should:

1. maintain one shared host-level limiter across workers;
2. avoid concurrent requests to the same publisher;
3. respect Retry-After;
4. exponentially back off on repeated 429/5xx responses;
5. cache successfully extracted article pages;
6. avoid fetching the page when feed content is already sufficient;
7. stop trying a site for a cooling-off period after repeated blocking;
8. review and follow applicable robots/terms requirements.

## Facts versus expression

A core content-design principle is to distinguish:

- the fact that an event happened;
- a publisher's copyrighted expression of that event.

AutoNewsRoller is intended to use publishers as evidence, construct a FactPackage, and then create an original script.

It is not intended to copy and republish full articles verbatim.

The current Ollama prompt explicitly requests an original neutral narration and limits facts to the supplied FactPackage.

The project should continue avoiding automatic reuse of:

- full article text;
- long quoted passages;
- publisher photographs without permission/license;
- publisher video clips without permission/license;
- publisher graphics/infographics without permission/license;
- logos in ways that imply endorsement.

## Guardian-specific caution

The Guardian's official RSS help page states that its feeds can be used for personal, non-commercial purposes in accordance with its terms.

Reference:

https://www.theguardian.com/help/feeds

Its current terms also describe Guardian site/content use as personal and non-commercial unless other permission/licensing applies.

Reference:

https://www.theguardian.com/help/terms-of-service

Therefore, do not assume that the presence of a Guardian RSS feed grants blanket permission to use Guardian content as input to a monetized automated publishing business.

If commercial publishing is the target, either obtain appropriate permission/license or design the source pack around inputs whose terms fit the intended commercial use.

## NASA-specific note

NASA's official media guidelines state that NASA content is generally not subject to copyright in the United States and permits broad factual/editorial use subject to important exceptions.

Reference:

https://www.nasa.gov/nasa-brand-center/images-and-media/

Important exceptions/conditions include:

- third-party copyrighted material appearing on NASA properties;
- protected NASA insignia/logotype/identifiers;
- endorsement implications;
- some commercial/promotional uses;
- identifiable persons and other clearance issues.

NASA should be acknowledged as the source where applicable.

The repository's authoritativePrimary flag for NASA JPL concerns verification logic, not copyright clearance.

Those are separate questions.

## Other publishers

BBC and Ars Technica are currently configured as discovery/verification sources.

Their current terms should be reviewed before commercial deployment.

Do not infer commercial reuse permission solely because an RSS URL is publicly accessible.

## Source trustTier is not a legal field

trustTier affects internal ranking/confidence heuristics.

It does not mean:

- licensed;
- fair use;
- legally safe;
- politically neutral;
- factually perfect;
- endorsed by the project.

Likewise authoritativePrimary means "the system is allowed to accept this as a first-party authoritative source for verification purposes." It does not mean "all media from this source can be freely republished."

## Safe content architecture for an automated news channel

A stronger production policy is:

    discover event from feeds
        -> corroborate event
        -> extract facts
        -> record source links
        -> write original script
        -> create original/procedural visuals or properly licensed/public-domain visuals
        -> publish with source attribution where appropriate

This minimizes dependence on copying publisher expression.

## AI-generated visuals and factual news

Generated illustrations should be clearly treated as illustrative rather than documentary evidence.

Avoid generating realistic scenes that could be mistaken for actual footage of:

- disasters;
- crimes;
- wars;
- named people;
- specific real-world incidents.

For many news videos, procedural headline/timeline/source cards are more defensible and less misleading than synthetic "photorealistic event footage."

The current ComfyUI path is optional for this reason as well as for reliability.

## Platform-level rules

Even when source acquisition is lawful, a publishing platform may have its own:

- reused-content rules;
- AI-content disclosure rules;
- monetization requirements;
- copyright systems;
- misinformation policies;
- impersonation rules.

Those need to be considered separately from source-site permissions.

## Before commercial unattended deployment

Recommended checklist:

- review every enabled source's current feed/site/API terms;
- remove or disable sources whose terms do not fit the intended use;
- use original scripts;
- use original, licensed, or appropriately reusable visuals;
- store source/provenance records;
- implement per-domain rate limiting;
- implement a review path for disputed/sensitive stories;
- keep records of source URLs and timestamps;
- periodically re-review source terms because they can change.
