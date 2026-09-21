# Context compression correctness fixes (pending CI verification)

Working-tree baseline: `b3d839b7381f1a28ae4d45a298f90113aa33a9f4`.
Reference: the previously reviewed deepseek-ai/deepseek-harness `0d1f50007f9bca3f52b06e1c3074fa14d5fb0720`.
This file is a fix record for this round, not release notes; nothing compiled, no Kotlin unit tests run, no APK delivered.

## Fix scope

- The summarize function is now a read-only transform and no longer implicitly trims the whole history. Callers first select the prefix that may change, then trim, re-snapshot, and re-select. The outer check that the protected region is unchanged remains.
- In-flight normal pressure, manual requests, and over-limit recovery share one selection function; the idle UI uses the same function. The tail is uniformly kept at 16% of the main-model window's token estimate, falling back to whole tool-pairing boundaries; on confirmed over-limit or when local history is shorter than the tail budget, the newest whole unit is kept. The strategy options and whole-round protection branches are deleted.
- The pre-request pressure check no longer skips the first round. After successful in-flight trimming the pressure is re-estimated; if it has cleared, the summary model is not called again.
- Same-model request DTOs and raw JSON are built from the same trimmed snapshot; the summary entry point verifies they correspond and fails before the request on finding a stale replay. Provider-specific fields on the raw JSON are still preserved directly, not replayed through DTO reconstruction.
- Outer cancellation is bound to the dedicated summary controller; the UI `runInterruptible` thread interrupt is also forwarded to network resources. Success, exception, and format-repair paths share cancellation/timeout handling and unbind at the end. Cancellation takes priority over ordinary network errors and summary acceptance.
- The UI no longer swallows cancellation and returns a trim result. Trim-only and new-summary generation use different markers and prompts; summary exceptions display a normalized end reason (e.g. `OUTPUT_LIMIT`) while truncated output is still rejected.
- UI-side trimming archives with status `ready` only and does not claim `committed` before the candidate result is committed. No new cross-database/archive transaction guarantees.

## Regression cases

16 new cases, spread across:

- `AgentCompactionPruningTest`: long protected tail unchanged, summarize function has no trim side effects, post-trim replay, stale-replay rejection, empty selection archives nothing.
- `AgentSummaryPipelineTest`: cancellation reaches live resources, unbind at end, UI thread interrupt, format-repair cancellation, truncation cause.
- `AgentCompressionStrategyTest`: idle/active single-round shared token protected region, over-limit never splits parallel tool batches, first-request pressure compression, consecutive manual compressions in one run.
- `AgentContextCompactionUiTest`: trimming never masquerades as a new summary, Runtime trim events recognized when UI history lags.

One old Loop case's pressure input adjusted: from a fake 8-token window to a 100k window with 95k of request billing, still verifying compression before the next request after a tool batch completes.

## Verification done and not done

- Done: `git diff --check`, parsing and duplicate-resource-name checks on changed resource XML, new-test counts, and call-site review.
- Not done: Kotlin/Android compilation, `testDebugUnitTest`, real-device long-conversation/cancellation regression. Tests existing does not mean tests passing.
- Version number, build workflow, and persisted conversation data untouched. Actions had not been triggered before commit; the user has now authorized commit, push, and workflow runs with no version-number change.
- Per user authorization, GitHub Actions is the path, with passing unit tests and builds as the delivery condition; actual results are whatever the corresponding run records show.

## Remaining limitations

This round does not implement upstream's full event-log transactions, image offloading, archive backup/import, or exact tokenizers; nor does it relax the archive restriction on sensitive tool results. The 16% window retention uses su's existing token estimate, not exact provider pricing. Abnormal endings in historical logs only prove summary acceptance failed; they do not guarantee that raising the output cap would fix it; this round does not mask that failure by accepting a half-received summary.

## Follow-up real-device OUTPUT_LIMIT fix

The Actions run `35152181469` for `cf1f970` passed unit tests and the Release build plus upload. Real-device logs then showed 62 history items with a 1-chunk summary ending in `OUTPUT_LIMIT`; passing unit tests does not mean a real provider completed the summary.

- The final summary target length is separated from the model's hard generation budget; normal windows start from an 8192 generation budget; small windows tighten to a quarter of the window, floor 1024, and the final input-budget check can still reject undersized windows.
- Only on an explicit `OUTPUT_LIMIT` with no tool calls, the truncated result is discarded and retried once at up to double the budget, capped at 16384; the retry budget is also limited by window, actual input estimate, and safety margin.
- Retries keep the original input, conversation, and thinking tier; truncated content is never injected into history; cancellation and the existing 120-second total request deadline cover the retry, and final summary-length acceptance is not relaxed.
- 7 new cases cover successful retry, attempt cap, window margin, cancellation, and no-retry on tool calls and content filtering, with final summary length still bounded.
- New request-budget/thinking-tier and retry-reason logging; no user body text or credentials logged.
- The local unit-test attempt stalled on the Gradle distribution download and timed out without running tests; final verification still goes through GitHub Actions. Version number unchanged.
