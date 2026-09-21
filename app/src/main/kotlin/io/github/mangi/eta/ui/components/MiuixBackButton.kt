package io.github.mangi.eta.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * Shared back button for second-level pages; keeps icon, semantics, RTL direction, and touch target consistent.
 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    IconButton(
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
        )
    }
}
