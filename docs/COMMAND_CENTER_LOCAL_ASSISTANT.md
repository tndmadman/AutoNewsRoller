# Command Center local Ollama assistant (CH-07)

This adds a persistent, read-only chat to the Command Center. It runs against the local Ollama endpoint reachable from the COMMAND CENTER process. If the controller and GPU worker run on different machines, Ollama at the worker's 127.0.0.1 is not directly reachable from the controller; configure a trusted and protected network endpoint or run a local model on the controller.

## Setup

1. Pull latest main; restart the Command Center and refresh the browser.
2. Open LOCAL OLLAMA / OPERATIONS CHAT / CH-07 at the bottom of the dashboard.
3. Send a question about current jobs, failures, finished videos, meme scoring, workers, or pipeline prompts.
4. Browse PROMPT LIBRARY for the source implementations of the actual pipeline system prompts. The source is a template, not necessarily the exact per-story interpolated request.

Default configuration (defaults.txt):

    commandCenterAssistantModel=llama3.1:8b
    commandCenterAssistantOllamaUrl=http://127.0.0.1:11434/api/generate
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
