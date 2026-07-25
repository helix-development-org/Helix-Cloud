package org.helix.api.addon

/**
 * The node's central storage connection, exposed to addons that bring
 * external components needing the same database (for example Helix-Guard,
 * whose IGuard plugin persists violations in PostgreSQL).
 *
 * @property mode storage mode from `node.toml` (`json`, `postgres`, `mongodb`).
 * @property url connection URL (JDBC for postgres, connection string for mongo).
 * @property user database user.
 * @property password database password.
 * @property database database name.
 * @property poolSize configured connection pool size.
 */
data class StorageConnection(
    val mode: String,
    val url: String,
    val user: String,
    val password: String,
    val database: String,
    val poolSize: Int,
)
