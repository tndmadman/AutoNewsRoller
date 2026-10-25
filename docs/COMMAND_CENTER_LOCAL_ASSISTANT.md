# Command Center local Ollama assistant (CH-07)

This adds a persistent, read-only chat to the Command Center. The assistant uses Ollama's native multi-turn `/api/chat` endpoint with the current question as the last user message; prior conversation turns and current operational context are separate. Enter sends, Shift+Enter inserts a newline, and the SEND button remains available. It runs against the local Ollama endpoint reachable from the COMMAND CENTER process. If the controller and GPU worker run on different machines, Ollama at the worker's 127.0.0.1 is not directly reachable from the controller; configure a trusted and protected network endpoint or run a local model on the controller.

## Setup

1. Pull latest main; restart the Command Center and refresh the browser.
2. Open LOCAL OLLAMA / OPERATIONS CHAT / CH-07 at the bottom of the dashboard.
3. Send a question about current jobs, failures, finished videos, meme scoring, workers, or pipeline prompts.
4. Browse PROMPT LIBRARY for the source implementations of the actual pipeline system prompts. The source is a template, not necessarily the exact per-story interpolated request.

Default configuration (defaults.txt):

    commandCenterAssistantModel=llama3.1:8b
    commandCenterAssistantOllamaUrl=http://127.0.0.1:11434/api/generate

The assistant automatically maps the configured `/api/generate` URL to `/api/chat` (or accepts an explicit `/api/chat` URL). The news, framing and meme generators still use their existing structured `/api/generate` requests. The chat does not change those pipelines.
    commandCenterAssistantContextTokens=16384
    commandCenterAssistantMaxOutputTokens=1400

## Current information and memory

Each question receives a fresh controller snapshot: scanning state, counts, worker status/capabilities, up to 25 selected active/relevant stories, recent video versions, recent generated scripts and scene prompts when sidecar files exist, and relevant allowlisted prompt source excerpts. The prompt library covers normal news scripts and hooks, political framing, meme worthiness, meme hooks and scripts, comedy reviewer, Comfy and visual prompts, image inspection, TTS, pipeline wiring and worker logic.

Only bounded excerpts are sent to the LLM to fit the context window; the entire codebase, all article bodies, and all conversations are not loaded into a single request. Browsing a prompt via the library fetches that source file separately. Where the video provenance sidecar exists, recent actual generated narration/hook/scene data is loaded separately from static prompt templates. Without such an artifact the assistant should say it cannot inspect exact runtime prompts.

Chat saves up to 120 user/assistant message records to data/command_center_assistant_history.json and restores them across browser and server restarts. Up to 12 recent messages are provided to the model on each turn. CLEAR CHAT deletes saved history but does not affect queues or generated videos. The file is ordinary on-disk JSON, accessible to the operator and users with filesystem privileges.

## Read-only and security controls

Assistant endpoints reuse the Command Center token and refuse service when the controller binds beyond localhost without AUTONEWS_TOKEN. Never expose the controller or Ollama endpoint publicly. Source files are allowlisted; arbitrary paths are rejected. Input messages are limited to 4000 characters, request bodies to 16 KB, history to 120 turns, and generated reply lengths are bounded. Browser rendering uses textContent rather than HTML for untrusted chat/model/source text.

The assistant cannot read arbitrary files, run shell commands, change config, queue or scrap videos, or trigger scans. Live state is refreshed at request time; prior conversation does not override current status. RSS text, prior conversation and source excerpts are untrusted data, not system instructions. Human verification remains important.

## API

- GET /api/assistant — saved model and conversation.
- POST /api/assistant/chat — body: JSON object containing message.
- DELETE /api/assistant/history — clear saved chat.
- GET /api/assistant/prompts — allowlisted source catalog.
- GET /api/assistant/prompt?id=meme-script — full readable source for selected catalog ID.

## Validation and known limits

The Java self-test uses a mock local Ollama endpoint to check prompt construction, historical persistence/reload, source allowlisting, and clear. Browser tests exercise visible chat, reload and safe rendering. Neither test establishes actual local model runtime performance or humor quality. There is no semantic indexing of all old videos, no capture of every raw historical Ollama request, and no natural-language mutation of system state. Those can be considered separately with access-control and redaction work.

## Diagnosing an unrelated repeated answer

The chat now sends the latest user question as its final Ollama chat turn, with saved conversation roles and separately supplied live state. A small model may still repeat an old reply; an exact duplicate answer to a different user question triggers one targeted retry and returns a visible error instead of saving the same off-topic reply again. Questions about meme-worthiness analyses should reference the `memeComplete` total and `memeRecentAnalyzed` for the configured recent time window—not the RSS feed count or the number of generated video versions. The context remains bounded and is not an unlimited memory of all source data.
