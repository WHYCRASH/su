# Summary request diagnostics

This change only adds diagnostics: no extra model requests, and no change to chunking,
retries, submission, or the 120-second total deadline.

## Correlation fields

- `group`: one compaction operation, covering each chunk, the merge, and format repair.
- `request`: one completeCompression call. Thinking-level fallback and output-limit
  retries share this ID and the original total deadline.
- `phase`: `chunk_N_of_M`, `merge`, with format repair suffixed as `/repair`.
- `attempt`: the attempt number within this call; event stats are isolated per attempt.
- `remaining_ms`: the total budget remaining when this attempt started, not a fresh
  120 seconds.
- `endpoint` / `streaming_text`: the interface and streaming capability declared by the
  adapter; service addresses and credentials are never printed.

## Timing and counts

Uses a monotonic clock, recording response headers, the first non-empty body segment,
the latest ProviderEvent, and the latest body event.

`text_delta_chars` / `thinking_delta_chars` are total received incremental UTF-16
lengths, not token counts, final summary lengths, or network byte counts. Adapters that
report no event are marked `unknown`, which must not be read as proof of no network
data. ProviderEvent does not include every SSE heartbeat or underlying socket activity;
`last_event_ago_ms` is not a network idle timeout.

Response headers and the first body segment are recorded immediately; at most one
progress line every 15 seconds while waiting. Success, exception, and watchdog
termination each produce a snapshot. Failure logs are written before the cancellation
check so the underlying exception survives instead of being replaced by a local timeout.

`reason=total_deadline` means the local total deadline; `cancelled` means cancellation;
`transport_timeout_or_interrupted_io` means an InterruptedIOException arrived directly,
which is not the same as a server timeout. Everything else is
`provider_or_validation_failure`, judged together with the safe error code and
observed_stage. Observed body text does not mean transfer is still in progress; read it
together with the time since the last event/body.

"Summary response:" only means the completion returned, not that the summary passed validation or
was submitted; the outer "Mid-run compaction committed:" log line and the checkpoint committed
flag are final. (The quoted strings are the verbatim log markers emitted by the runtime code.)

## Privacy and overhead

No prompts, summary bodies, thinking content, tool parameters, HTTP header contents,
URLs, API keys, or raw exception messages are recorded. Each attempt keeps only
timestamps, counts, and bounded state; the existing watchdog is reused, no threads added.
Log-path exceptions are isolated with runCatching and cannot change the summary result.

## Verification

New SummaryRequestDiagnosticsTest cases for deterministic clocks, concurrent counting,
rate limiting, missing events, and content non-leakage. GitHub Actions unit-test and
Release build runs are still needed; this document does not claim cloud or on-device
verification.
