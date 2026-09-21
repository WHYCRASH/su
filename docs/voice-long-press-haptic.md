# Long-press haptics on the record button

The recording entry uses Compose `combinedClickable`. The system component fires one
haptic at the long-press threshold by default, while the feature callback had already
called `TouchHaptics.longPress` before that, so some devices vibrated twice.

The record button and the recognition indicator now disable `combinedClickable`'s
built-in haptics and call `TouchHaptics.longPress` exactly once at the feature
long-press callback entry. Regular taps still produce one `TouchHaptics.click`, and
opening recording mode behaves as before.
