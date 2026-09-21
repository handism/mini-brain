package com.minibrain.data.db.util

object SqlUtils {
    /**
     * Escapes standard SQL wildcards for use in a LIKE query.
     * Use with `ESCAPE '\'` in the SQL query.
     */
    fun escapeForLike(query: String): String {
        return query.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
