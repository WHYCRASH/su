# Compaction mechanism alignment (worktree change)

Reference inspected: deepseek-ai/deepseek-harness commit
`0d1f50007f9bca3f52b06e1c3074fa14d5fb0720`, especially
`packages/compaction/compaction-basic/src/{config,region,summarizer}.ts` and
`packages/compaction/compaction-image-offload/src/project-message.ts`.

## Adopted behavior

- Remove summary target tokens from both dialogs, preferences consumers, callbacks,
  IPC and loop policy. Old persisted target values are ignored, not migrated into
  output limits. No goal-length validation or length-only rewrite request.
- Summary generation ceiling defaults to 16384 on large windows so a long
  checkpoint is not thrown away at 8192 and retried. Small windows still reserve
  input room. A truncated response is never committed. One bounded generation
  retry remains when the first ceiling is below 16384.
- Split only when the selected prefix cannot fit one summarizer request.
  Chunk budgeting uses the same generation reserve as the summary request, so
  the first chunk cannot exceed the input budget after raising the ceiling.
  Archive attach is validated before the summary request.
- Automatic pressure at 80% (DeepSeek harness thresholdRatio), owned by Runtime rather than stream UI callbacks.
  Re-evaluate the full request after actual shrink, with at most one additional
  pressure pass. Confirmed overflow recovery remains bounded and fail-closed.
- Queue manual compaction without cancelling SSE. Finish the response and the
  complete tool batch before maintenance. Natural final output does not generate
  a new continuation prompt/request or change the turn identity/reasoning setting.
  Seal maintenance together with the existing end-of-run steering inlet.
- Do not show actual compression progress during mere queueing; switch only on
  ContextCompactionStarted. Preserve existing displayed messages/reveal state.
- Typed recursive JSON projection for text-only summaries; no regex on serialized
  JSON. Media becomes an explicit unread placeholder; nested tool content/text is
  preserved. Same-model replay still uses structured media capability filtering.
- Runtime logs start, failure stage/checkpoint and committed result, including
  preflight failures before any network summary request.

## Intentional su adaptations

- Continuation tail uses retainRatio 0.16 (DeepSeek harness). 128k -> 20.5k, 500k -> 80k.
  These are selection budgets, not permission to split tool batches or truncate a
  large newest unit. Explicit full-turn protection remains available.
- Summary sections follow harness fact-density headings. The live summary lands
  one replacement checkpoint (surfaceOp=replace), not a growing UUID index.
  Older originals remain in the session archive JSON; the user-facing sheet
  still strips the footnote.
- Keep raw checkpoint archives, full retained tail, same turn IDs,
  format validation, prefix-budget splitting (up to 32 chunks), timeout and
  cancellation propagation. This is not a wholesale TypeScript backend port.
- Failure never commits the failed summary. Independently committed tool pruning
  or an earlier successful pressure pass remains valid and is reported separately.

## Verification status

Unit tests updated/added for queueing during streamed text, no extra reply,
unchanged turn/visible deltas, failed summary fallback, cancellation/sealed inlet,
80% pressure and bounded extra pass, 16% tail selection, old IPC target
ignored, normal long checkpoint acceptance vs non-shrinking rejection, and large
escaped JPEG / nested-media projection without mutating original data.

Static diff/reference/XML checks only so far. No Gradle build, Actions run, or
installation triggered; full tests and packaging await user build confirmation.
Version remains 5.2.8 / 2026091601.
