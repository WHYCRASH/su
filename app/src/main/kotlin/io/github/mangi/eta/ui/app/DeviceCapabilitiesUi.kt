package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal data class DeviceCapabilitiesUi(
    val root: RootAccessState,
    val tools: AgentToolCapabilities,
) {
    val accessibilityAvailable: Boolean get() = tools.accessibilityAvailable
}


@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilitiesUi {
    val context = LocalContext.current.applicationContext
    val root by RootAccess.state.collectAsState()
    var tools by remember { mutableStateOf(AgentToolCapabilities.capture(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                tools = AgentToolCapabilities.capture(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }
    return DeviceCapabilitiesUi(root, tools.copy(rootAvailable = root.isGranted))
}

internal fun RootAccessState.description(context: Context): String = context.getString(
    when {
        isChecking -> R.string.capability_root_checking
        status == RootAccessStatus.GRANTED -> R.string.capability_root_granted
        status == RootAccessStatus.UNAVAILABLE -> R.string.capability_root_unavailable
        status == RootAccessStatus.TIMED_OUT -> R.string.capability_root_timeout
        else -> R.string.capability_root_not_granted
    },
)
