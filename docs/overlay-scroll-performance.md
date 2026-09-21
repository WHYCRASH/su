# Overlay orb and conversation scroll performance

## On-device evidence (before optimization)

The sample covered three windows at once: a 184×184 orb, a 1216×2640 full-screen
decor window, and the conversation Activity. Across three consecutive sampling
intervals, the conversation added 767 frames / 149 janky frames (19.4%); the two
anonymous overlay layers showed 760 / 203 (26.7%) and 760 / 210 (27.6%) respectively.
Anonymous frame stats are not force-mapped to specific windows. In 8 seconds of atrace,
the Activity drew 446 times and the two overlays 893 times combined; main-thread
postAndWait totaled about 3436 ms (wall-clock wait, not exclusive CPU time). That run's
main-thread doFrame averaged 10.34 ms, p95 12.99 ms, max 40.66 ms. The raw on-device
diagnostics live in /workspace/overlay-scroll-diag/ and are not committed to the repo;
the logs are not a basis for percentage improvement claims.

## Changes

- Remove the extra full-screen black dim and the rotating blur halo window instead of
  just hiding pixels while the animation keeps running.
- Keep the 56 dp orb, the expanded action bubble, pause/resume/stop/supplement controls,
  and the final result card.
- The orb keeps its breathing effect, but driven by a monotonic clock at 50 ms updates
  (20 Hz max) instead of subscribing to every screen frame with an infinite animation.
- Breathing values are read only at draw time and do not drive recomposition/layout;
  drawWithCache reuses the radial gradient.
- No continuous loop is created outside the running state; the coroutine is cancelled
  when leaving the composition.
- The orb subscribes only to the phase, not to tool-detail or round changes. Window tags
  Eta Agent Orb / Controls / Result make later attribution easier.

## Verification

Covers breathing range and frequency, draw updates not triggering recomposition,
stillness on pause/end/failure, resume and unload cancellation, small window sizes, and
no full-screen halo window; the existing visibility and control-action tests keep
running. On-device improvement still needs a retest with the optimized build under the
same conversation and swipe conditions; passing unit tests must not be reported as a
frame-rate gain.
