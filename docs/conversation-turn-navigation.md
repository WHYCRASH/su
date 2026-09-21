# Chat turn navigation

- "Up/down" means browsing direction: browsing toward older messages shows the up arrow,
  toward newer messages the down arrow.
- Short press jumps to the previous/next round's user question; long press jumps to the
  top/bottom of the conversation; the button hides at the corresponding boundary.
- Round anchors come from the projected timeline index; grouped tool steps, compression
  restores, and same-round appended instructions never count as new rounds.
- The current round is whichever round holds the top of the viewport; short-pressing up
  jumps to the previous round; jumping again from the first/last round falls back to the
  top/bottom.
- A 12dp direction threshold suppresses small hand jitter; the accumulated amount below
  the threshold clears on release. Fling, streaming follow-bottom, and button jumps do
  not change the direction.
- Auto-follow pauses during a jump; a manual drag can cancel the jump. Follow-bottom
  resumes at the bottom.
- A tap calls `TouchHaptics.click` once, a long press calls `TouchHaptics.longPress`
  once, and release adds no extra tap; combinedClickable's built-in haptics are off and
  no list-edge haptics fire mid-jump. App/system haptic settings are respected.

## Verification

10 test bodies for the pure strategy were run standalone with the existing Kotlin
compiler on Linux, all passing; no local Android build was run.

- `ConversationTurnNavigationTest`: direction, jitter, programmatic scroll, round
  targets, and first/last boundaries.
- `ConversationTurnProjectionTest`: real indexes under tool grouping, appended
  instructions, and hidden restored messages.
- `ConversationTurnNavigationButtonTest`: short/long press in both directions, callback
  counts, explicit haptic counts, and no stacking on top of built-in haptics.
- Full Android/Compose tests run on GitHub Actions after approval; on-device haptics
  and streaming scroll still need acceptance.

## Short dwell fix at appended content (scroll transition kept)

- The user asked to keep the scroll transition: skipping appended messages means "do
  not treat appended messages as docking stops", not "do not travel past appended
  messages on screen". The animation-cancelling b8f43c5 approach was withdrawn,
  unreleased.
- Round navigation scrolls frame by frame inside a single scroll mutation, keeping speed
  across messages; before the real target is measured, it does not slow down toward a
  distance estimated from the average height of intermediate messages.
- It slows down and aligns only after the real target question appears; if one frame
  overshoots the target, that frame's measurement aligns exactly with no reverse
  compensation animation.
- Finger drags can still cancel the scroll; one haptic per tap/long-press and the
  existing round filtering are unchanged.
- Regression tests must show animation mid-positions, crossing appended messages, no
  reversal, and exact final alignment; plus continuous motion before the target,
  slowdown at the target, frame step sizes, and dynamic remaining distance.
- 5 motion-strategy tests were run standalone with the existing Kotlin compiler, all
  passing; full Compose scrolling and real dwell improvement still need cloud and
  on-device verification.
