package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceInputConfig
import top.yukonga.miuix.kmp.preference.SwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun VoiceModeSettingsScreen(onBack: () -> Unit, onOpenReadAloud: () -> Unit) {
    val context = LocalContext.current
    val config by VoiceInputConfig.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { VoiceInputConfig.load(context) }

    MiuixScaffoldPage(title = stringResource(R.string.voice_mode_title), onBack = onBack) {
        item(key = "universal") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.voice_mode_universal_enable),
                    checked = config.conversationEnabled,
                    onCheckedChange = { VoiceInputConfig.save(context, config.copy(conversationEnabled = it)) },
                    insideMargin = PaddingValues(16.dp),
                )
                ArrowPreference(
                    title = stringResource(R.string.voice_mode_universal),
                    summary = stringResource(R.string.voice_mode_universal_settings_summary),
                    insideMargin = PaddingValues(16.dp),
                    onClick = onOpenReadAloud,
                )
                Text(
                    text = stringResource(R.string.voice_mode_universal_settings_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
