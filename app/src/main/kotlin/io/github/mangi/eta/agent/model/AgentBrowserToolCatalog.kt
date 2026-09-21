package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "Drive the shared off-screen multi-tab agent browser (up to 3 tabs); it never switches to an external browser. Each call performs exactly one action. Supports new_tab, close_tab, list_tabs; tab_id selects a tab, otherwise the current tab is used. navigate accepts a full URL, a domain, a search query, /workspace, /var/minis, or minis://. Available as desktop_chrome / mobile_chrome. get_cookies returns only a summary plus /var/minis/offloads/env_cookies_xxx.sh; secrets never enter the conversation. Usually navigate first, then use get_readable to extract the Markdown body, or find_elements / get_backbone to learn the structure. Base selectors on the current DOM; a search box may be a textarea or contenteditable, not necessarily an input. When scroll_and_collect returns 0 items, check the structure and keyword filters first instead of concluding the page is empty. go_back/go_forward move the history index one step at a time. To hand a URI explicitly to an external app, use open_uri.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The single browser action to perform in this call.")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("new_tab")
                                            .put("close_tab")
                                            .put("list_tabs")
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                            .put("execute_js")
                                            .put("get_backbone")
                                            .put("hover")
                                            .put("fetch")
                                            .put("get_cookies")
                                            .put("set_cookies")
                                            .put("set_user_agent")
                                            .put("set_viewport")
                                            .put("scroll_and_collect")
                                            .put("wait_for_dom_stable"),
                                    ),
                            )
                            .put("tab_id", JSONObject().put("type", "integer").put("description", "Target tab ID from list_tabs or a previous result; never auto-switches to another tab when it is missing."))
                            .put("url", JSONObject().put("type", "string").put("description", "Target for navigate or fetch; navigate also accepts a domain or search query."))
                            .put("selector", JSONObject().put("type", "string").put("description", "CSS selector used by click, type, hover, get_text, find_elements, scroll, or wait_for_selector."))
                            .put("text", JSONObject().put("type", "string").put("description", "Text for type to enter. It is only sent to the tool and never shown in the run summary."))
                            .put("submit", JSONObject().put("type", "boolean").put("description", "Whether type submits its form after entering text; default false."))
                            .put("coordinate_x", JSONObject().put("type", "integer").put("description", "Viewport X coordinate for click, type, or hover; use together with coordinate_y."))
                            .put("coordinate_y", JSONObject().put("type", "integer").put("description", "Viewport Y coordinate for click, type, or hover; use together with coordinate_x."))
                            .put("amount", JSONObject().put("type", "integer").put("description", "Scroll distance in pixels for scroll."))
                            .put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down")).put("description", "Scroll direction for scroll."))
                            .put("offset", JSONObject().put("type", "integer").put("description", "Text start offset for get_readable or get_text; default 0."))
                            .put("max_chars", JSONObject().put("type", "integer").put("description", "Maximum text characters returned by get_readable or get_text."))
                            .put("read_image", JSONObject().put("type", "boolean").put("description", "Whether screenshot attaches the image for the model to see directly; default true."))
                            .put("full_page", JSONObject().put("type", "boolean").put("description", "Whether screenshot captures as much of the full page as possible; default false. Height is capped."))
                            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Timeout in milliseconds for navigate, wait_for_selector, or wait_for_dom_stable."))
                            .put("timeout", JSONObject().put("type", "integer").put("description", "Timeout in milliseconds for wait_for_dom_stable; kept for compatibility with timeout_ms."))
                            .put("script", JSONObject().put("type", "string").put("description", "Script for execute_js to run. It executes inside an async function; await and return are supported."))
                            .put("user_agent", JSONObject().put("type", "string").put("enum", JSONArray().put("desktop_chrome").put("mobile_chrome")).put("description", "Browser identity for set_user_agent."))
                            .put("max_depth", JSONObject().put("type", "integer").put("description", "Maximum DOM depth for get_backbone; default 5."))
                            .put("viewport_width", JSONObject().put("type", "integer").put("description", "Viewport width for set_viewport; at least 320."))
                            .put("viewport_height", JSONObject().put("type", "integer").put("description", "Viewport height for set_viewport; at least 320."))
                            .put("reset", JSONObject().put("type", "boolean").put("description", "When true, set_viewport restores the current UA's default viewport."))
                            .put("item_selector", JSONObject().put("type", "string").put("description", "CSS selector scroll_and_collect uses to collect items."))
                            .put("scroll_count", JSONObject().put("type", "integer").put("description", "Number of scrolls for scroll_and_collect; default 5."))
                            .put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Keyword filter for get_cookies or scroll_and_collect."))
                            .put("fuzzy", JSONObject().put("type", "boolean").put("description", "Keyword matching for get_cookies: when true the name must contain all keywords; when false it exactly matches any keyword. Default true."))
                            .put("cookies", JSONObject().put("type", "string").put("description", "JSON array string for set_cookies; each item must at least contain name and value.")),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
