package org.helix.node.services

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * The Velocity modern-forwarding secret of this installation.
 *
 * Modern forwarding is the default and refuses to run with an empty
 * secret, so a blank `proxy.forwardingSecret` in the node config must not
 * end up as an empty `forwarding.secret` in every proxy workspace (the
 * proxy would crash-loop on first start). When the operator configures no
 * secret, a random one is generated once and persisted next to the node
 * config, keeping proxies and backends prepared at different times in
 * agreement.
 */
object ForwardingSecret {
    /**
     * Returns the configured secret, or the persisted generated one,
     * generating and persisting it on first use.
     *
     * @param configured the `proxy.forwardingSecret` config value.
     * @param file location of the generated secret, conventionally
     *   `Helix/config/forwarding.secret`.
     * @return a non-blank forwarding secret.
     */
    fun resolve(configured: String, file: Path): String {
        if (configured.isNotBlank()) {
            return configured
        }
        val existing = runCatching { Files.readString(file).trim() }.getOrNull()
        return existing?.takeIf { it.isNotBlank() } ?: generateInto(file)
    }

    private fun generateInto(file: Path): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val secret = bytes.joinToString("") { "%02x".format(it) }
        Files.createDirectories(file.parent)
        Files.writeString(file, secret)
        return secret
    }
}
