package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Personal data reads only bind verified system providers, never exposing URIs, table names or SQL to the model.
 * Root is used to call providers guarded by signature permissions; query conditions always use fixed columns and controlled keywords.
 */
internal class AgentPersonalDataTools(
    private val root: BoundedRootCommandExecutor,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? =
        when (name) {
            "search_media" -> searchMedia(args)
            "search_audio" -> searchAudio(args, recordingsOnly = false)
            "search_recordings" -> searchAudio(args, recordingsOnly = true)
            "search_files" -> searchFiles(args)
            "search_calendar_events" -> searchCalendarEvents(args)
            "search_contacts" -> searchContacts(args)
            "search_call_history" -> searchCallHistory(args)
            "search_messages" -> searchMessages(args)
            "search_downloads" -> searchDownloads(args)
            else -> null
        }

    private fun searchMedia(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_media",
        uri = "content://media/external/file",
        projection = listOf("_id", "_display_name", "mime_type", "relative_path", "datetaken", "date_modified", "_size"),
        sort = "date_modified DESC",
        searchableColumns = listOf("_display_name", "relative_path"),
        fixedWhere = "media_type=1",
        args = args,
    ) { row ->
        row.put("uri", "content://media/external/file/${row.optString("_id")}")
    }

    private fun searchAudio(args: JSONObject, recordingsOnly: Boolean): AgentModelClient.ToolResult = query(
        tool = if (recordingsOnly) "search_recordings" else "search_audio",
        uri = "content://media/external/audio/media",
        projection = listOf("_id", "title", "_display_name", "artist", "album", "relative_path", "duration", "date_modified", "_size"),
        sort = "date_modified DESC",
        searchableColumns = listOf("title", "_display_name", "artist", "relative_path"),
        fixedWhere = if (recordingsOnly) "relative_path LIKE '%Record%'" else null,
        args = args,
    ) { row ->
        row.put("uri", "content://media/external/audio/media/${row.optString("_id")}")
    }

    private fun searchFiles(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_files",
        uri = "content://media/external/file",
        projection = listOf("_id", "_display_name", "mime_type", "relative_path", "date_modified", "_size"),
        sort = "date_modified DESC",
        searchableColumns = listOf("_display_name", "relative_path"),
        fixedWhere = "media_type=0",
        args = args,
    ) { row ->
        row.put("uri", "content://media/external/file/${row.optString("_id")}")
    }

    private fun searchCalendarEvents(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_calendar_events",
        uri = "content://com.android.calendar/events",
        projection = listOf("_id", "title", "description", "eventLocation", "dtstart", "dtend", "allDay", "calendar_displayName"),
        sort = "dtstart DESC",
        searchableColumns = listOf("title", "description", "eventLocation"),
        fixedWhere = "deleted=0",
        args = args,
    )

    private fun searchContacts(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_contacts",
        uri = "content://com.android.contacts/contacts",
        projection = listOf("_id", "display_name", "lookup", "has_phone_number", "contact_last_updated_timestamp"),
        sort = "display_name COLLATE LOCALIZED ASC",
        searchableColumns = listOf("display_name"),
        fixedWhere = null,
        args = args,
    ) { row ->
        row.put("uri", "content://com.android.contacts/contacts/${row.optString("_id")}")
    }

    private fun searchCallHistory(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_call_history",
        uri = "content://call_log/calls",
        projection = listOf("_id", "number", "name", "date", "duration", "type", "geocoded_location"),
        sort = "date DESC",
        searchableColumns = listOf("number", "name"),
        fixedWhere = null,
        args = args,
    )

    private fun searchMessages(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_messages",
        uri = "content://sms",
        projection = listOf("_id", "thread_id", "address", "body", "date", "type", "read"),
        sort = "date DESC",
        searchableColumns = listOf("address", "body"),
        fixedWhere = null,
        args = args,
    )

    private fun searchDownloads(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_downloads",
        uri = "content://downloads/my_downloads",
        projection = listOf("_id", "title", "description", "mime_type", "total_size", "lastmod", "status", "local_uri"),
        sort = "lastmod DESC",
        searchableColumns = listOf("title", "description"),
        fixedWhere = null,
        args = args,
    )

    private fun query(
        tool: String,
        uri: String,
        projection: List<String>,
        sort: String,
        searchableColumns: List<String>,
        fixedWhere: String?,
        args: JSONObject,
        transform: (JSONObject) -> Unit = {},
    ): AgentModelClient.ToolResult {
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val keyword = args.optString("query").trim()
        val where = combineWhere(fixedWhere, keyword.takeIf(String::isNotBlank)?.let {
            searchableColumns.likeClause(it)
        })
        val command = buildString {
            append("content query --uri ").append(shellQuote(uri))
            append(" --projection ").append(shellQuote(projection.joinToString(":")))
            where?.let { append(" --where ").append(shellQuote(it)) }
            append(" --sort ").append(shellQuote(sort))
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES)
        if (!result.ok || PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)) {
            return sensitive(error(rootErrorCode(result), "Personal data source is temporarily unavailable"))
        }
        val items = PersonalDataContentParser.parseRows(result.stdout, projection)
            .take(limit)
            .map { row -> row.also(transform) }
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("items", JSONArray(items))
                .put("count", items.size)
                .put("truncated", result.truncated || items.size == limit)
                .toString(),
        )
    }

    private fun List<String>.likeClause(keyword: String): String {
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("'", "''")
        val value = "'%$escaped%'"
        return joinToString(" OR ", prefix = "(", postfix = ")") { column ->
            "LOWER($column) LIKE LOWER($value) ESCAPE '\\'"
        }
    }

    private fun combineWhere(first: String?, second: String?): String? = when {
        first == null -> second
        second == null -> first
        else -> "($first) AND ($second)"
    }


    private fun rootErrorCode(result: BoundedRootCommandExecutor.Result): String = when {
        result.errorCode.isNotBlank() -> result.errorCode
        result.timedOut -> "PERSONAL_DATA_QUERY_TIMEOUT"
        else -> "PERSONAL_DATA_UNAVAILABLE"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val QUERY_TIMEOUT_MS = 15_000L
        const val MAX_OUTPUT_BYTES = 512 * 1024
    }

}

internal object PersonalDataContentParser {
    fun hasProviderFailure(stdout: String, stderr: String): Boolean =
        sequenceOf(stdout, stderr).any { output ->
            output.contains("Error while accessing provider:") ||
                output.contains("java.lang.IllegalArgumentException:") ||
                output.contains("java.lang.SecurityException:")
        }

    fun parseRows(source: String, columns: List<String>): List<JSONObject> =
        source.lineSequence()
            .filter { it.startsWith("Row:") }
            .map { line ->
                JSONObject().also { row ->
                    columns.forEach { column -> value(line, column, columns)?.let { row.put(column, it) } }
                }
            }
            .toList()

    private fun value(line: String, column: String, columns: List<String>): String? {
        val following = columns.filterNot { it == column }.joinToString("|") { Regex.escape(it) }
        return Regex("(?:^|,\\s*|\\s)${Regex.escape(column)}=(.*?)(?=,\\s*(?:$following)=|$)")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.takeUnless { it == "null" }
    }
}
