package io.github.mangi.eta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The standard section-title-plus-card combination; every Provider screen uses it. */
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            SmallTitle(title)
        }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            content()
        }
    }
}

/** Vendor brand icon in original colors; the asset already includes a background and safe area suited to circular cropping. */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .padding(end = 6.dp)
            .size(24.dp)
            .clip(CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** Known vendors use brand icons; unknown sources keep the generic protocol-type icon. */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    when (provider) {
        is CustomProviderSetting -> PreferenceIcon(
            icon = Icons.Rounded.Dns,
            modifier = modifier,
        )
        else -> PreferenceIcon(
            icon = Icons.Rounded.Language,
            modifier = modifier,
        )
    }
}

internal enum class TagChipTone { Normal, Emphasized }

/** Small capsule chip for capability and status tags. */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MiuixTheme.colorScheme.secondaryContainer
            foreground = MiuixTheme.colorScheme.onSecondaryContainer
        }
        TagChipTone.Emphasized -> {
            background = MiuixTheme.colorScheme.primaryContainer
            foreground = MiuixTheme.colorScheme.onPrimaryContainer
        }
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
