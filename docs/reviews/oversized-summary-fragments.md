# Oversized @-reference / single-history-item summary input budget fix

## Confirmed root cause

Real-device failing checkpoint c7c490fa-10fb-466e-a8f6-fff40f04a0e0: the first user message is a @-conversation reference, 248911 characters, locally estimated at 131195 tokens. The summary window caps at 128000 internally, before output reserve and safety margin.
The old splitMessages ran `require(cost <= budget)` on every whole-boundary unit, so an ordinary user message could trigger "single whole tool unit exceeds the summary-model input budget". Not an over-long generated summary, and not an over-large tool result per se.

## Implementation

- Chunking budgets and actual sends share compressionModel / summaryInput, including system prompt, summary instructions, tool definitions, output reserve, and media projection; no more rough fixed-1024-overhead chunking that only discovers the overflow at send time.
- Whole tool-batch boundaries preserved. Normal-size messages keep structured replay with original system/tools/session; replay offsets around oversized fragments are still computed against original message indexes.
- On oversized units, the summary path alone projects the whole unit into a read-only exhibit carrying role and tool_call_id, then slices it under the real request budget. Original history, the protected tail, and archive files are untouched; no unpaired tool-protocol messages are generated.
- Each fragment carries original message range, UTF-16 range, and total character count; the read-only notice repeats on every fragment, stating the @-reference is background, the tools are already-recorded history — never execute, never read fragment edges as task completion.
- Text fragments fully cover the projected text — no trim, no hard cut, no split Unicode surrogate pairs; structured images reuse the existing media projection instead of treating Base64 as body text.
- The full chunk plan completes before the first summary call. At most 32 chunks per level; over-plan fails fast instead of spending half the requests before discovering there are too many chunks.
- When intermediate summaries cannot merge at once, hierarchical merging under the same budget follows, at most 4 levels; every non-final level must genuinely reduce body-text tokens or the run fails with the original history kept.
- Original normal-end validation, truncation rejection, cancellation, the 120-second per-request deadline, output-truncation limited retry, and the final context-actually-shrunk check are kept.
- Logs carry only correlation IDs, phases, message ranges, character counts, chunk counts, and budgets — never reference body text.

## Unchanged items

- @-reference caps, auto-compression thresholds, original-text retention policy, turnId, and the pause/continue/stop chain are untouched.
- The summary model's actual window cap is untouched; no summary-target-length tiers reintroduced.
- No raw conversation/checkpoint data modified; existing redaction and archive transactions stay with their original callers.
- Normally fitting history costs no extra requests; large inputs take multiple summaries — no promise that latency stays flat.
- Fragments keep all projected text, which does not mean model summaries are lossless or bound to succeed.

## Tests and status

25 new tests (AgentSummaryTextFragmentsTest 7 items, AgentOversizedSummaryTest 18 items):

- 131k-token-plus long references, full text coverage, Unicode, blank/separator markers.
- Actual send budgets, original history/tail unchanged, replay offsets, whole tool batches read-only-ified, media Base64 filtered.
- Chunk caps, tiny windows, stale-replay rejection, bad-tool-pairing rejection, cancellation, fragment failure, truncation failure.
- Layered merging, no-progress exit, max-depth exit, failed final merges never committed, ordinary inputs in a single request.

`git diff --check` and lexical bracket checks on modified files run; no Kotlin compilation, unit tests, or on-device retests yet.
Locally based on feat/tts at 0bf32e5; already-delivered TTS changes kept, uncommitted /workspace/Eta code never mixed in.
Nothing committed, pushed, or run through GitHub Actions this round; version number unchanged.
