package io.github.mangi.eta.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL aggregates only: reject oversized snapshots before Room allocates all row DTOs. */
internal object BackupDatabaseBudget {
    private val tables = listOf(
        "model_providers", "provider_models", "conversations", "conversation_messages",
        "conversation_context_checkpoints", "conversation_state", "conversation_folders",
        "skill_registry", "mcp_servers",
    )
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_ROWS = 50_000L

    fun validate(database: SupportSQLiteDatabase, conversationId: String? = null) {
        var bytes = 0L
        var rows = 0L
        for (table in tables) {
            if (conversationId != null && table !in setOf("conversations", "conversation_messages", "conversation_context_checkpoints")) continue
            val columns = database.query("PRAGMA table_info(`$table`)").use { cursor ->
                val names = mutableListOf<String>()
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    require(name.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*"))) { "Unsupported database column name" }
                    names += name
                }
                names
            }
            require(columns.isNotEmpty()) { "Backup database table missing: $table" }
            val sum = columns.joinToString(" + ") { "COALESCE(LENGTH(CAST(`$it` AS BLOB)), 0)" }
            val where = if (conversationId == null) "" else
                " WHERE `${if (table == "conversations") "id" else "conversation_id"}` = ?"
            val args = if (conversationId == null) emptyArray<Any>() else arrayOf<Any>(conversationId)
            database.query("SELECT COUNT(*), COALESCE(SUM($sum), 0) FROM `$table`$where", args).use { cursor ->
                check(cursor.moveToFirst())
                rows += cursor.getLong(0)
                bytes += cursor.getLong(1)
            }
            require(rows <= MAX_ROWS && bytes <= MAX_BYTES) {
                "Backup metadata exceeds the current safe snapshot limit; export a single session first or reduce the data"
            }
        }
    }
}
