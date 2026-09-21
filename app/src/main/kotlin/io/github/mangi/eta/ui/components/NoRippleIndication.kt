package io.github.mangi.eta.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/** For settings lists: drop Material's default press ripple and shadow. */
internal object NoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}

@Composable
internal fun WithoutPressRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides NoRippleIndication, content = content)
}
