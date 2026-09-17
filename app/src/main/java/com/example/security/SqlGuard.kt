package com.example.security

/**
 * SECURITY (Agent #14) — item 7: SQL injection hardening helpers.
 *
 * Room already binds every `@Query` parameter, so the injection surface is NOT the
 * WHERE clause — it is anywhere a value is concatenated into SQL *text*:
 *   1. ORDER BY / GROUP BY column identifiers (cannot be bound as parameters)
 *   2. LIKE patterns (a user-supplied `%` becomes a wildcard)
 *   3. any future @RawQuery
 *
 * This object is the single allowlist chokepoint for (1) and (2).
 */
object SqlGuard {

    /** Allowlisted sort keys -> hard-coded SQL fragments. Never interpolate input. */
    private val SORT_COLUMNS: Map<String, String> = mapOf(
        "recent"   to "createdAt DESC",
        "oldest"   to "createdAt ASC",
        "name"     to "type COLLATE NOCASE ASC",
        "worn"     to "timesWorn DESC, createdAt DESC",
        "colour"   to "color COLLATE NOCASE ASC, type COLLATE NOCASE ASC",
        "season"   to "season ASC, createdAt DESC"
    )

    const val DEFAULT_SORT = "recent"

    /**
     * Maps an untrusted sort key to a hard-coded fragment.
     * Anything not in the allowlist falls back to the default — it never reaches SQL.
     */
    fun safeOrderBy(requested: String?): String =
        SORT_COLUMNS[requested?.lowercase()?.trim()] ?: SORT_COLUMNS.getValue(DEFAULT_SORT)

    /** Escapes SQL LIKE metacharacters so a search box cannot widen its own query. */
    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /** Wraps user input for a `LIKE :q ESCAPE '\'` parameter. */
    fun likeParam(raw: String): String = "%${escapeLike(raw.trim())}%"

    /** Hard cap on user-supplied free text persisted to the DB. */
    const val MAX_TEXT_LEN = 120
    fun clamp(raw: String): String = raw.trim().take(MAX_TEXT_LEN)
}
