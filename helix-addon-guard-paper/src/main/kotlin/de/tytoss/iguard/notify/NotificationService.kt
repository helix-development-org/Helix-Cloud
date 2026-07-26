package de.tytoss.iguard.notify

import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.model.EvidenceFamily
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Posts high-confidence incidents and bans to a Discord webhook as rich embeds. Config-driven and
 * hot-reloadable via [DynamicConfig.notifications]; all HTTP happens on a dedicated single thread so it
 * never touches a server tick, with a per-player cooldown to avoid spamming the channel.
 */
class NotificationService(
    private val dynamic: AtomicReference<DynamicConfig>,
    private val logger: Logger
) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "iguard-discord").apply { isDaemon = true } }
    private val lastSent = ConcurrentHashMap<UUID, Long>()

    /** Posts a flagged-incident embed when Discord notifications are enabled and thresholds pass. */
    fun incident(
        playerId: UUID, playerName: String, confidence: Double,
        families: Set<EvidenceFamily>, evidenceCount: Int, action: String?, recipe: String
    ) {
        val cfg = dynamic.get().notifications
        if (!cfg.discordEnabled || cfg.webhookUrl.isBlank()) return
        if (confidence < cfg.minConfidence) return
        if (onCooldown(playerId, cfg.cooldownMillis)) return
        val pct = (confidence * 100).toInt()
        val color = if (pct >= 80) 0xE23C3C else if (pct >= 50) 0xE2953C else 0xE2CF3C
        val embed = embed(
            title = "🚩 Flagged: $playerName",
            description = "Confidence **$pct%**" + (action?.let { "  •  `$it`" } ?: ""),
            color = color,
            fields = listOf(
                Triple("Families", families.joinToString().ifEmpty { "—" }, true),
                Triple("Evidence", evidenceCount.toString(), true),
                Triple("Recipe", recipe, true)
            )
        )
        post(cfg.webhookUrl, embed)
    }

    /** Posts a ban embed (auto or manual) when ban notifications are enabled. */
    fun ban(playerName: String, hours: Int, reason: String, actor: String) {
        val cfg = dynamic.get().notifications
        if (!cfg.discordEnabled || cfg.webhookUrl.isBlank() || !cfg.notifyBans) return
        val embed = embed(
            title = "⛔ Ban: $playerName",
            description = "Duration **${hours}h**",
            color = 0x8B0000,
            fields = listOf(
                Triple("Reason", reason, false),
                Triple("By", actor, true)
            )
        )
        post(cfg.webhookUrl, embed)
    }

    /** Stops the webhook IO thread; pending posts are discarded. */
    fun shutdown() = io.shutdownNow()

    private fun onCooldown(playerId: UUID, cooldown: Long): Boolean {
        if (cooldown <= 0) return false
        val now = System.currentTimeMillis()
        val prev = lastSent.put(playerId, now)
        return prev != null && now - prev < cooldown
    }

    private fun embed(title: String, description: String, color: Int, fields: List<Triple<String, String, Boolean>>): String {
        val fieldJson = fields.joinToString(",") { (name, value, inline) ->
            """{"name":"${esc(name)}","value":"${esc(value)}","inline":$inline}"""
        }
        return """{"embeds":[{"title":"${esc(title)}","description":"${esc(description)}","color":$color,"fields":[$fieldJson],"footer":{"text":"IGuard Anti-Cheat"}}]}"""
    }

    private fun post(url: String, body: String) {
        io.execute {
            runCatching {
                val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 4000; readTimeout = 4000
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) logger.warning("Discord webhook returned $code")
                conn.disconnect()
            }.onFailure { logger.warning("Discord webhook failed: ${it.message}") }
        }
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> {}
            '\t' -> append("\\t")
            else -> if (c < ' ') append(' ') else append(c)
        }
    }
}
