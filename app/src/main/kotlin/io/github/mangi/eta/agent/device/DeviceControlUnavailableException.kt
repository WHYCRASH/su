package io.github.mangi.eta.agent.device

/** When the connection between coordinate conversion and actions is lost, still return a recognizable accessibility state to the tool boundary. */
internal class DeviceControlUnavailableException : IllegalStateException(
    "The su accessibility service has been disconnected. Re-enable the service and observe the screen again.",
)
