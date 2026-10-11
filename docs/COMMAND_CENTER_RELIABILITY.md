# Command Center interaction and state reliability

## Client synchronization

The Command Center is still a dependency-free vanilla JavaScript application. Stories, videos, workers, and feeds are reconciled by stable IDs rather than removing all cards on every refresh. Leaf text/attribute changes are applied in place; cards containing active text selections, focus, or pointer interactions are not mutated during that interaction. Buttons in story and archive rows use delegated `data-command` handlers instead of inline JavaScript.

Mutations are tracked by story ID. Conflicting controls are disabled while a request is pending, and the UI immediately acknowledges the first click. Network timeouts do not imply that the server rolled back the operation; the UI requests an authoritative snapshot before suggesting another user action.

Snapshots are single-flight, with a follow-up refresh when events or actions arrive during an in-flight request. A mutation acknowledgement invalidates snapshots started before that acknowledgement. Worker and feed SSE events are applied directly to their keyed records; events received after a snapshot request began are replayed over that older snapshot before rendering. A five-second snapshot poll still reconciles missed events, offline workers, counts, and reconnections.

## API compatibility and idempotency

For Command Center POST calls, clients should send a unique `operationId` string (up to 128 characters) in the JSON body. The store persists the last 2,000 operation identifiers and rejects an identifier reused for a different operation. Duplicate requests for an already-queued or already-producing story do not clear worker leases, retries, or queue timestamps. MAKE/WORTH on completed stories is rejected; RE-MAKE requires a completed version.

Video publication mutations accept explicit `versionId` values so archived records are not modified inadvertently. Existing blank version-ID clients are still supported by the store and resolve to the currently selected version, but the UI supplies exact versions. Upload/scrap history is append-only for real status changes, not identical duplicates.

Claim responses for remote VIDEO jobs include `claimId`. Updated workers echo it on:
- progress POST as JSON `claimId`;
- video PUT in the `X-Claim-Id` header;
- complete/fail POST as JSON `claimId`;
- heartbeat POST as JSON `claimId`.

The store checks the active status, worker ID, and claim ID before accepting worker-originated production updates; stale callbacks from earlier leases cannot affect the current claim when clients supply these fields. The endpoints accept missing claim IDs for compatibility with existing deployed workers, so upgrading remote workers together with the controller is recommended.

Mutations return HTTP 409 for invalid state transitions and HTTP 500 for failed required persistence. The controller writes JSON state using temporary-file replacement and reduces redundant worker-heartbeat writes to once every five seconds, while production progress/actions still persist normally.

## Tests

From the repository root:

```bash
npm install
npx playwright install chromium firefox
npm run test:browser
bash build_linux.sh
java -cp build/classes autonewsroller.Main --self-test
```

GitHub Actions runs the existing Windows and Linux validation plus Chromium and Firefox Playwright tests. The browser suite exercises pointerdown/refresh/pointerup, rapid clicks, text selection, diagnostics expansion, archive version correctness, stale snapshots, simulated HTTP failures, lost responses, live SSE updates, and 120-story/90-feed/five-worker stress data. The baseline DOM-churn comparison loads the original main JavaScript to measure actual card removal versus the updated renderer.

## Operational limitations

Older deployed worker binaries that do not include claim IDs cannot receive full stale-lease protection until upgraded. The 2,000-item idempotency ledger is bounded rather than permanent; the legal transition guards and status-based duplicate checks still apply. Unrecoverable storage errors return a server error, but an in-memory state change may already have occurred; investigate disk access and restart/reconcile before manually repeating ambiguous requests. Production runs with actual GPU/video generation and browser performance traces remain separate from mocked UI regression testing.
