package io.github.mangi.eta.agent.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Cookie export per the MiniS hub skill convention: writes an env file, keeps plaintext out of tool results. */
internal object BrowserCookieOffload {
    fun envName(cookieName: String): String {
        val body = cookieName.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return "COOKIE_" + body.ifBlank { "UNNAMED" }
    }

    fun fileName(host: String, timestampMs: Long): String {
        val safeHost = host.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "site" }
        return "env_cookies_${safeHost}_$timestampMs.sh"
    }

    fun matches(name: String, keywords: List<String>, fuzzy: Boolean): Boolean {
        if (keywords.isEmpty()) return true
        return if (fuzzy) {
            keywords.all { name.contains(it, ignoreCase = true) }
        } else {
            keywords.any { name.equals(it, ignoreCase = true) }
        }
    }

    fun keywords(args: JSONObject): List<String> {
        args.optJSONArray("keywords")?.let { array ->
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }
        return args.optString("keywords")
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun script(url: String, cookies: List<Pair<String, String>>): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# cookies for $url")
        appendLine("# source this file in the Linux environment; do not print values")
        cookies.forEach { (name, value) ->
            append("export ")
            append(envName(name))
            append('=')
            append(shellSingleQuote(value))
            appendLine()
        }
    }

    fun write(
        directory: File,
        host: String,
        url: String,
        cookies: List<Pair<String, String>>,
        timestampMs: Long = System.currentTimeMillis(),
    ): File {
        directory.mkdirs()
        val file = File(directory, fileName(host, timestampMs))
        file.writeText(script(url, cookies))
        return file
    }

    fun namesArray(cookies: List<Pair<String, String>>): JSONArray {
        val array = JSONArray()
        cookies.forEach { array.put(it.first) }
        return array
    }

    private fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
