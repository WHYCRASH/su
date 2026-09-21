package io.github.mangi.eta.ui.haptics

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/**
 * Conversation text selection. Vibrate once when press-and-hold starts a selection; extending by swipe, dragging a handle, or canceling the selection does not vibrate.
 *
 * Compose selection changes fire [HapticFeedbackType.TextHandleMove] repeatedly. On HyperOS, if converted to a long press,
 * it would slide along. Here we swallow the system selection haptic and instead trigger it once ourselves on long-press timeout.
 * Observe events only; do not consume down/move/up: the native selection needs the full gesture to end dragging and show the copy menu.
 * Link accidental-tap prevention is handled in the UriHandler layer, so it no longer steals the selection's release event.
 */
@Composable
internal fun HapticSelectionContainer(
    modifier: Modifier = Modifier,
    selectionState: SelectionState = rememberSelectionState(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val parent = LocalHapticFeedback.current
    val haptic = remember(parent) { SelectionHapticFeedback(parent) }
    val uriHandler = io.github.mangi.eta.ui.markdown.rememberChatUriHandler(LocalUriHandler.current)
    val linkGuard = remember { SelectionLinkGuard() }
    val guardedUriHandler = remember(uriHandler, linkGuard) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (linkGuard.canOpenLink()) uriHandler.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(
        LocalHapticFeedback provides haptic,
        LocalUriHandler provides guardedUriHandler,
    ) {
        SelectionContainer(
            state = selectionState,
            modifier = modifier.pointerInput(view, linkGuard) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    linkGuard.onDown()
                    val slop = viewConfiguration.touchSlop
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    try {
                        try {
                            withTimeout(longPressTimeout) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: return@withTimeout
                                    if (!change.pressed) {
                                        linkGuard.onUp(change.uptimeMillis - down.uptimeMillis, longPressTimeout)
                                        return@withTimeout
                                    }
                                    if ((change.position - down.position).getDistance() > slop) {
                                        return@withTimeout
                                    }
                                }
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            linkGuard.onLongPress()
                            fireSelectionStartHaptic(view)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    linkGuard.onUp(change.uptimeMillis - down.uptimeMillis, longPressTimeout)
                                    break
                                }
                            }
                        }
                    } finally {
                        // Cancellation must not leave keyboard/accessibility link activation blocked.
                        linkGuard.onGestureFinished()
                    }
                }
            },
            content = content,
        )
    }
}

private var lastSelectionStartAt = 0L

private fun fireSelectionStartHaptic(view: View) {
    val now = SystemClock.uptimeMillis()
    if (now - lastSelectionStartAt < 80L) return
    lastSelectionStartAt = now
    TouchHaptics.longPress(view)
}

private class SelectionHapticFeedback(
    private val parent: HapticFeedback,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticFeedbackType) {
            HapticFeedbackType.LongPress,
            HapticFeedbackType.TextHandleMove,
            -> Unit
            else -> parent.performHapticFeedback(hapticFeedbackType)
        }
    }
}

internal fun isSelectionLongPressRelease(elapsedMillis: Long, timeoutMillis: Long): Boolean =
    elapsedMillis >= timeoutMillis
