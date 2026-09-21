package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** Default contract for the screen-observation tool, shared by the model schema, the executor, and the run trace. */
internal object AgentScreenObservationContract {
    const val DEFAULT_INCLUDE_SCREENSHOT = false
    const val DEFAULT_INCLUDE_UI_TREE = true
    const val DEFAULT_MAX_NODES = 60
    const val MIN_MAX_NODES = 1
    const val MAX_MAX_NODES = 120

    data class Options(
        val includeScreenshot: Boolean,
        val includeUiTree: Boolean,
        val maxNodes: Int,
    )

    fun resolve(arguments: JSONObject): Options = Options(
        includeScreenshot = arguments.optBoolean(
            "include_screenshot",
            DEFAULT_INCLUDE_SCREENSHOT,
        ),
        includeUiTree = arguments.optBoolean(
            "include_ui_tree",
            DEFAULT_INCLUDE_UI_TREE,
        ),
        maxNodes = arguments.optInt("max_nodes", DEFAULT_MAX_NODES),
    )
}
