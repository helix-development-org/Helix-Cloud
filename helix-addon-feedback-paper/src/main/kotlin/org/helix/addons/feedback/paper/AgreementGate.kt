package org.helix.addons.feedback.paper

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The tester agreement (plan 1 §1): a tester (`feedback.tester`, not staff) who has not accepted the current
 * agreement version is frozen after joining – no moving, interacting, chatting or commands except
 * `/testervereinbarung` – and sees the rules as a book with „Annehmen“/„Ablehnen“. Accepting stores the version on the
 * node and unfreezes; declining kicks with a friendly text.
 *
 * @property plugin the owning plugin.
 * @property client node client.
 * @property text resolves a translation key for a player (MiniMessage).
 */
class AgreementGate(
    private val plugin: JavaPlugin,
    private val client: FeedbackNodeClient,
    private val text: (Player, String) -> String,
) : Listener {
    private val mini = MiniMessage.miniMessage()

    /** Frozen players and the agreement version they have to accept. */
    private val frozen = ConcurrentHashMap<UUID, Int>()

    /**
     * Checks a player off the main thread and freezes them if needed (call on join).
     *
     * @param player the joining player.
     */
    fun check(player: Player) {
        if (!player.hasPermission(PERMISSION_TESTER) || player.hasPermission(PERMISSION_STAFF)) return
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val state = client.agreement(player.uniqueId) ?: return@Runnable
            if (!FreezeRules.mustAccept(true, state.acceptedVersion, state.version, false)) return@Runnable
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                frozen[player.uniqueId] = state.version
                showBook(player)
            })
        })
    }

    /**
     * @param player a player.
     * @return whether the player is frozen until accepting.
     */
    fun isFrozen(player: Player): Boolean = frozen.containsKey(player.uniqueId)

    /** Opens the agreement book (also via `/testervereinbarung`). */
    fun showBook(player: Player) {
        val pages = (1..PAGES).map { page -> mini.deserialize(text(player, "agreement.page.$page")) }.toMutableList()
        pages += mini.deserialize(text(player, "agreement.page.choice"))
            .append(Component.newline()).append(Component.newline())
            .append(mini.deserialize(text(player, "agreement.accept")).clickEvent(ClickEvent.runCommand("/$COMMAND annehmen")))
            .append(Component.newline()).append(Component.newline())
            .append(mini.deserialize(text(player, "agreement.decline")).clickEvent(ClickEvent.runCommand("/$COMMAND ablehnen")))
        player.openBook(Book.book(mini.deserialize(text(player, "agreement.title")), Component.text("KronlandMC"), pages))
    }

    /**
     * Handles `/testervereinbarung [annehmen|ablehnen]`.
     *
     * @param player the player.
     * @param choice the argument, null shows the book.
     */
    fun choose(player: Player, choice: String?) {
        val version = frozen[player.uniqueId]
        when (choice?.lowercase()) {
            "annehmen", "accept" -> {
                if (version == null) {
                    player.sendMessage(mini.deserialize(text(player, "agreement.already")))
                    return
                }
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    val reply = client.accept(player.uniqueId, version)
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (reply.ok) {
                            frozen.remove(player.uniqueId)
                            player.sendMessage(mini.deserialize(text(player, "agreement.accepted")))
                        } else {
                            player.sendMessage(mini.deserialize(text(player, "error.unavailable")))
                        }
                    })
                })
            }
            "ablehnen", "decline" -> player.kick(mini.deserialize(text(player, "agreement.kick")))
            else -> showBook(player)
        }
    }

    /** Forgets a leaving player. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        frozen.remove(event.player.uniqueId)
    }

    /** Frozen players keep their position (looking around is allowed). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!isFrozen(event.player)) return
        val from = event.from
        val to = event.to
        if (FreezeRules.blocksMove(from.x, from.y, from.z, to.x, to.y, to.z)) {
            event.to = from.clone().apply { yaw = to.yaw; pitch = to.pitch }
        }
    }

    /** Frozen players may not interact. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (isFrozen(event.player)) event.isCancelled = true
    }

    /** Frozen players may not interact with entities. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (isFrozen(event.player)) event.isCancelled = true
    }

    /** Frozen players may not drop items. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isFrozen(event.player)) event.isCancelled = true
    }

    /** Frozen players take no damage. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (isFrozen(player)) event.isCancelled = true
    }

    /** Frozen players may not chat. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (isFrozen(event.player)) {
            event.isCancelled = true
            event.player.sendMessage(mini.deserialize(text(event.player, "agreement.frozen")))
        }
    }

    /** Frozen players may only run the agreement command. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (isFrozen(event.player) && !FreezeRules.commandAllowed(event.message)) {
            event.isCancelled = true
            event.player.sendMessage(mini.deserialize(text(event.player, "agreement.frozen")))
        }
    }

    /** Names and permissions. */
    companion object {
        /** The agreement command. */
        const val COMMAND = "testervereinbarung"

        /** Testers have to accept the agreement. */
        const val PERMISSION_TESTER = "feedback.tester"

        /** Staff is never frozen. */
        const val PERMISSION_STAFF = "feedback.staff"

        /** Rule pages before the choice page. */
        const val PAGES = 3
    }
}
