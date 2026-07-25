package org.helix.addons.guard

import org.helix.api.addon.StorageConnection

/**
 * The PostgreSQL connection IGuard is configured with, inherited from the
 * node's central storage instead of being panel-editable.
 *
 * @property host database host.
 * @property port database port.
 * @property database database name.
 * @property username database user.
 * @property password database password.
 * @property ssl whether the JDBC URL requests SSL.
 */
data class GuardDatabase(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val ssl: Boolean,
) {
    /** Factory helpers around the node storage connection. */
    companion object {
        /** Placeholder used while the node does not run on PostgreSQL. */
        val UNCONFIGURED = GuardDatabase("127.0.0.1", 5432, "iguard", "iguard", "", ssl = false)

        /**
         * Derives IGuard's database settings from the node storage.
         *
         * @param connection the node's storage connection.
         * @return parsed settings, or `null` when the node does not run on
         *   PostgreSQL (IGuard requires it).
         */
        fun fromStorage(connection: StorageConnection?): GuardDatabase? {
            if (connection == null || !connection.mode.equals("postgres", ignoreCase = true)) {
                return null
            }
            val withoutScheme = connection.url.removePrefix("jdbc:postgresql://")
            val hostPort = withoutScheme.substringBefore('/')
            val tail = withoutScheme.substringAfter('/', missingDelimiterValue = "")
            val pathDatabase = tail.substringBefore('?').takeIf { it.isNotBlank() }
            val parameters = tail.substringAfter('?', missingDelimiterValue = "")
            return GuardDatabase(
                host = hostPort.substringBefore(':').ifBlank { "127.0.0.1" },
                port = hostPort.substringAfter(':', missingDelimiterValue = "5432").toIntOrNull() ?: 5432,
                database = pathDatabase ?: connection.database,
                username = connection.user,
                password = connection.password,
                ssl = parameters.contains("ssl=true") || parameters.contains("sslmode=require"),
            )
        }
    }
}
