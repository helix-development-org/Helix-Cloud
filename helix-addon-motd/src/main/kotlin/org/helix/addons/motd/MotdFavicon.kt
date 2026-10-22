package org.helix.addons.motd

import java.util.Base64

/**
 * Server-list icon helpers.
 *
 * Minecraft only accepts a 64×64 PNG as server icon. Profiles store the icon
 * as plain Base64 (no `data:` prefix); an empty value means "use the network
 * icon" bundled with this addon (`/favicon.png`, the KronlandMC crown seal).
 */
object MotdFavicon {
    /** Required edge length of a server-list icon in pixels. */
    const val SIZE: Int = 64

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * The bundled network icon as Base64.
     *
     * @return Base64 of `/favicon.png`, or an empty string when the resource is missing.
     */
    val default: String by lazy {
        MotdFavicon::class.java.getResourceAsStream("/favicon.png")
            ?.use { Base64.getEncoder().encodeToString(it.readBytes()) }
            ?: ""
    }

    /**
     * Normalizes a user-supplied icon: strips a `data:image/png;base64,` prefix and whitespace.
     *
     * @param raw the supplied value.
     * @return the plain Base64 string.
     */
    fun normalize(raw: String): String = raw.trim().substringAfter("base64,").filterNot { it.isWhitespace() }

    /**
     * Checks that a Base64 value is a 64×64 PNG.
     *
     * @param base64 plain Base64 (see [normalize]).
     * @return `null` when valid, otherwise a short English reason.
     */
    fun validate(base64: String): String? {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return "favicon is not valid Base64"
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return "favicon must be a PNG"
        val width = int(bytes, 16)
        val height = int(bytes, 20)
        return if (width == SIZE && height == SIZE) null else "favicon must be ${SIZE}x$SIZE (got ${width}x$height)"
    }

    /**
     * The icon a profile actually shows.
     *
     * @param profile the profile.
     * @return the profile's own icon, or [default] when it has none.
     */
    fun effective(profile: MotdProfile): String = profile.favicon.ifBlank { default }

    private fun int(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or (bytes[offset + 3].toInt() and 0xFF)
}
