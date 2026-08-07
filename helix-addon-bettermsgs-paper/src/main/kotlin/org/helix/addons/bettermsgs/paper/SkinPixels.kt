package org.helix.addons.bettermsgs.paper

import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Resolves player faces as 8x8 pixel colors, so heads can be drawn as
 * colored font glyphs instead of head items.
 *
 * Skins are fetched from the player's game profile (online players carry
 * their textures; offline names are completed via the Mojang API), scaled
 * to the 8x8 face with the hat layer overlaid, and cached per name.
 */
class SkinPixels {
    private val cache = ConcurrentHashMap<String, IntArray>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /**
     * The cached face pixels of a player.
     *
     * @param name player name, case-insensitive.
     * @return 64 ARGB values (row-major 8x8), or `null` while unresolved.
     */
    fun cached(name: String): IntArray? = cache[name.lowercase()]

    /**
     * Fetches and caches a face; call off the main thread.
     *
     * @param name player name, case-insensitive.
     */
    fun fetch(name: String) {
        val key = name.lowercase()
        if (cache.containsKey(key) || !pending.add(key)) {
            return
        }
        try {
            val profile = Bukkit.getPlayerExact(name)?.playerProfile
                ?: Bukkit.createProfile(name).apply { complete(true) }
            val skinUrl = profile.textures.skin ?: return
            val skin = ImageIO.read(skinUrl) ?: return
            val scale = skin.width / 64
            val pixels = IntArray(64)
            for (y in 0..7) {
                for (x in 0..7) {
                    val face = skin.getRGB((8 + x) * scale, (8 + y) * scale)
                    val hat = skin.getRGB((40 + x) * scale, (8 + y) * scale)
                    pixels[y * 8 + x] = if (hat ushr 24 > 0x80) hat else face
                }
            }
            cache[key] = pixels
        } catch (_: Exception) {
            // unresolved skins simply stay undrawn
        } finally {
            pending.remove(key)
        }
    }
}
