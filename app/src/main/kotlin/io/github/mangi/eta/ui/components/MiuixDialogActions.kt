package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.R

/**
 * Dialog bottom button row: cancel on the left, confirm on the right, splitting the row evenly.
 * Follows the two-button convention from the official Miuix samples; every app dialog uses it.
 *
 * @param confirmText Confirm button text.
 * @param onCancel Cancel callback.
 * @param onConfirm Confirm callback.
 * @param modifier Root modifier.
 * @param cancelText Cancel button text.
 * @param cancelEnabled Whether the cancel button is enabled.
 * @param confirmEnabled Whether the confirm button is enabled.
 * @param destructive Whether confirm is a destructive action (uses the error color).
 */
@Composable
fun MiuixDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = cancelText ?: stringResource(R.string.action_cancel),
            onClick = {
                TouchHaptics.click(view)
                onCancel()
            },
            enabled = cancelEnabled,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = confirmText,
            onClick = {
                TouchHaptics.click(view)
                onConfirm()
            },
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = if (destructive) {
                ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.textButtonColorsPrimary()
            },
        )
    }
}
