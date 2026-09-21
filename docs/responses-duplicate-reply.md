# Duplicate display of Responses body text

2026-09-20 device session "test reply": conversation_messages held two assistant blocks
(indexes 1 and 2) for the same run/round, both with the body text for a short test reply,
while conversation_context_checkpoints held only one assistant record and one body copy.
This was not two independent runs. The original SSE and events of the finished task are
not retained, so the on-device records cannot confirm whether the difference was in
item_id, output_index, or content_index.

Responses end-state reconcileFinalPart used to rely entirely on content-block identity
matching; a failed match would create another BlockStart/Delta pair for the end-state
body. When a compatible API uses different IDs or indexes for streaming versus end
state, it can construct the same duplication path seen in the screenshot.

New bounded fallback: only when the round has one streamed body block and the end state
has one body segment, and the end-state source text contains the same streamed prefix,
reuse the original block and apply the end-state body. Multiple genuine segments still
match by identity, never by text dedup, so legitimately repeated content is not swallowed.
Existing conversation databases are not modified.

New tests cover end-state ID rewrites, output_index changes without IDs, completing an
already-finished partial body, and preserving legitimately repeated segments. Local
Gradle tests could not start because no Android SDK path is configured here; CI covers
them later. Existing duplicated messages are kept as-is: this code change must not be
read as proof that old records are cleaned up or that the actual SSE difference is
confirmed.
