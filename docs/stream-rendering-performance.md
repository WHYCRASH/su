# Streaming long-conversation render optimization

Problem: while streaming updates and manual scrolling happen together, history-related
computation still occupies the main thread. The device's existing StreamDiag shows a
conversation of about 3,900 messages; that log is not a controlled performance
experiment.

## This change

- `visibleTurnSpeechPrefaces` builds the read-aloud lead-in for the final answer in a
  single scan, replacing the per-final-answer scan from the start of history. In a test
  with 4,000 messages and 1,000 rounds, accesses dropped from 2,002,000 to 4,000, while
  keeping the existing semantics for supplementary messages, round boundaries, and
  hidden thinking. This is a workload change for that computation, not a claim of a
  500x overall frame-rate gain.
- LazyColumn declares timeline entries with batched items and stable keys and
  contentType. Content was lazily composed before too, but every list update still
  registered all items with forEach and pre-scanned every work process; work-process
  state is now initialized when its entry enters composition.
- The per-glyph fade-in saveLayer shrinks from the whole text range to the current glyph
  path bounds. Character reveal, bidirectional-text paths, and follow-bottom behavior are
  preserved; load is not reduced by pausing output while scrolling.
- New timeline.prefaces / timeline.project timings separate history projection from
  Markdown parsing in the software logs.

## RikkaHub comparison

The local `/workspace/rikkahub-source` checkout shows MarkdownBlock parsing on a
background mapLatest and ChatList using itemsIndexed with stable message keys. su
already parses, merges, and sessions in the background; the difference is not just the
parsing thread, since su also maintains tool progress, per-character reveal, and
auto-follow. A source comparison cannot prove equal smoothness on every device and
conversation.

## Verification limits

Unit tests verify identical output and traversal counts, and CI verifies compilation;
no old/new frame-time comparison on the same device with the same long text and
gestures has been done yet. Real 120Hz scroll feel still needs controlled testing after
installing the build.

## Long-answer drawing and user-gesture takeover

The installed device build hash is confirmed to still be 8a29a85. In StreamDiag session
9bc2f53a, an interval with only 6 messages and an answer of about 5,270 characters /
25,300 px layout height showed frame.draw averaging about 5.5 ms and frame.total about
18 ms, with history projection in the tens of microseconds. Short conversations have
long-text drawing pressure too; aggregate logs are not enough to blame any single
component for all the time.

- The top-level Markdown block uses a graphicsLayer to isolate the RenderNode display
  list, so a trailing invalidateDraw does not re-record every stable paragraph. No
  forced offscreen texture, no frozen streaming input.
- A markdown.blockDraw timer observes paragraph re-record counts and durations.
- NestedScrollConnection marks user takeover synchronously when a nonzero vertical
  scroll with UserInput arrives, returns Offset.Zero, and does not consume the gesture;
  follow-bottom rechecks the latest takeover state both after the awaited frame and
  after acquiring the scroll mutation.
- Gesture-takeover end keeps the existing drag/fling state and follow-bottom policy;
  no new auto-scroll-back is added.
- Unit tests/builds do not verify RenderNode gains on a real GPU; same-device,
  same-load swipe comparisons are still pending, so this must not be claimed as a
  cured-dropped-frames fix.
