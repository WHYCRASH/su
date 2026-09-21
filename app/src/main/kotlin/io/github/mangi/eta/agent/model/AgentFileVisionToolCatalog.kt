package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Generic capability for turning a file path into model visual input, independent of any personal-data Provider. */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "Reads one image or video from a user-specified path or system gallery URI and provides it to the model as visual input. For videos, a cover frame is extracted, so there is no need to transcode with the terminal first. In a Linux environment, /workspace, /var/minis, and minis:// paths are automatically mapped to host files, so there is no need to copy them to an Android path first. Call at most once per turn; to view multiple items, wait for the current result to come back and observe it, then read the next one in the following turn.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1_024)
                                .put("description", "Any absolute image or video path, file URI, or system gallery content URI; supports Linux /workspace and /var/minis paths; local paths are read by Root, and videos get an automatic cover frame extraction"),
                        ),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
