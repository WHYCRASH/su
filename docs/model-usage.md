# Model usage accounting

The model usage page reads from a request ledger; it no longer scales from the token
totals kept on chat messages.

ProviderClientFactory mints an independent requestId per model call. Repeat usage
callbacks for the same request update one record; different requests accumulate
separately even in the same conversation, round, or retry. Records use the actual
request configuration, never the model currently selected in the UI.

Covers factory requests for the main conversation, subagents, summary compaction,
title, and assistive vision. Subagents attribute to the parent conversation. Received
usage is saved before UI events dispatch; later cancellation or failure never erases
it. Missing usage is never estimated or fabricated. Input includes cache, and cache
must not be added to input again; output already includes the protocol-reported
reasoning tokens, which must not be accumulated twice. Usage belongs to the date the
request started; cross-midnight requests may differ from the server billing date.

Legacy conversation+round records stay readable: never cleared, never topped up by
platform differences. Overwritten or never-returned history usage cannot be rebuilt
automatically. At most the latest 4,000 line items per model are kept while cumulative
totals are kept independently; date filtering depends on the kept items, so early ranges
may be incomplete. Platform bills may also include other clients, server-side retries,
or different accounting, so client counts are never promised to equal the bill.

Verification: request identity, streaming-update idempotence, post-failure retention,
UI-consumption failure, no estimation on missing usage, history compatibility, time
filtering, and item trimming without lowering cumulative totals; full Android
regression runs on GitHub Actions.

## Conversation menu totals

The conversation token menu subscribes to the ledger's conversationTotalsV1 and
accumulates over the whole conversation with no date filtering. Totals are stored
separately from the latest 4,000 per-model line items; updates for the same request
add only the delta and never shrink on compaction, message deletion, or item trimming.
usageConversationId is independent of the network sessionId: title, vision-assist, and
compaction chunk/merge/repair work attribute explicitly to the parent conversation,
and subagents keep the parent conversation attribution.

On upgrade, before issuing any new request, a one-time baseline is built by reading
accumulated totals from old messages and compaction markers and deduplicating against
the existing request ledger. The two overlap and must not be added; keep the maximum
recorded value per field instead of proportionally backfilling unknown history. After
migration only real request usage accumulates, and restarts never stack it again.
Switching conversations validates the subscription result's owning ID so a stale number
from the previous conversation never flashes.
