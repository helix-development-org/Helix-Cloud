package org.helix.addons.bettermsgs.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.display.GuiFontConfiguration
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.slot.chestSlot
import de.tytoss.igui.slot.rectTo
import de.tytoss.igui.pagination.paginate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import kotlin.coroutines.CoroutineContext

/**
 * One message of a conversation, as served by the node addon.
 *
 * @property from sender name (lowercase).
 * @property text message text.
 * @property epochMs server receive time.
 */
@Serializable
data class ChatMessage(val from: String, val text: String, val epochMs: Long)

/**
 * History window response of `bettermsgs.history`.
 *
 * @property total total messages of the conversation.
 * @property offset window offset back from the newest message.
 * @property messages the window, oldest first.
 */
@Serializable
data class HistoryWindow(val total: Int = 0, val offset: Int = 0, val messages: List<ChatMessage> = emptyList())

/**
 * One contact of the phone home screen.
 *
 * @property name peer name (lowercase).
 * @property lastEpochMs time of the newest message.
 * @property unread unread message count.
 * @property online whether the peer is online on the network.
 */
@Serializable
data class Contact(val name: String, val lastEpochMs: Long = 0, val unread: Int = 0, val online: Boolean = false)

/**
 * Per-viewer chat state: the open conversation and its scroll position.
 *
 * @property peer conversation partner.
 */
class ChatState(val peer: String) {
    /** Scroll offset: messages back from the newest. */
    @Volatile
    var offset: Int = 0

    /** Total messages of the conversation, from the last fetch. */
    @Volatile
    var total: Int = 0

    /** The currently fetched window, oldest first. */
    @Volatile
    var messages: List<ChatMessage> = emptyList()
}

/**
 * BetterMSGs Paper component: a phone-style `/msg` GUI with a Discord-like
 * chat per conversation, rendered with IGui font textures. Conversations
 * live on the Helix node (`helix.bettermsgs` addon actions), so messaging
 * works across every server of the network.
 */
class BetterMsgsPlugin : org.bukkit.plugin.java.JavaPlugin(), Listener {
    private val json = Json { ignoreUnknownKeys = true }
    private val chats = ConcurrentHashMap<UUID, ChatState>()
    private val pendingInput = ConcurrentHashMap<UUID, String>()
    private var igui: IGui? = null
    private var phoneGui: GuiDefinition? = null
    private var chatGui: GuiDefinition? = null
    private var client: NodeClient? = null
    private lateinit var translations: Translations
    private lateinit var takeover: InventoryTakeover
    private lateinit var scope: CoroutineScope

    @Volatile
    private var packSha1: ByteArray? = null

    /** Operator-configured pack URL (`bettermsgs.packurl`), via bridge values. */
    @Volatile
    private var configuredPackUrl: String? = null

    /** Dispatcher running coroutines on the Bukkit main thread. */
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) {
                server.scheduler.runTask(this@BetterMsgsPlugin, block)
            }
        }
    }

    /**
     * Boots the GUI runtime: IGui install, texture registration, listeners,
     * schedulers and the `/msg` command wiring.
     */
    override fun onEnable() {
        val nodeClient = NodeClient.fromEnvironment()
        if (nodeClient == null) {
            logger.warning("No Helix environment found — BetterMSGs disabled.")
            server.pluginManager.disablePlugin(this)
            return
        }
        client = nodeClient
        translations = Translations(nodeClient)
        takeover = InventoryTakeover(dataFolder.toPath().resolve("inventories"))
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        server.pluginManager.registerEvents(this, this)
        scope.launch {
            val installed = IGui.install(this@BetterMsgsPlugin) {
                database(FileGuiTextureDatabase(dataFolder.toPath().resolve("textures.json")))
                fonts = GuiFontConfiguration(namespace = "bettermsgs")
                texture("bettermsgs.home", "", "gui", widthPixels = 176, heightPixels = 222)
                texture("bettermsgs.chat", "", "gui", widthPixels = 176, heightPixels = 222)
                for (index in 0..7) {
                    texture("bettermsgs.thumb$index", "${'' + index}", "gui", widthPixels = 4, heightPixels = 20)
                }
            }
            igui = installed
            phoneGui = buildPhoneGui(installed)
            chatGui = buildChatGui(installed)
            logger.info("BetterMSGs GUIs ready")
        }
        server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable {
                translations.sync()
                configuredPackUrl = client?.getJson("/api/v1/internal/bridge-values")
                    ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                    ?.get("bettermsgs.pack_url")
            },
            20L,
            100L,
        )
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollOpenChats() }, 20L, 20L)
        server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable {
                packSha1 = client?.getJson("/api/v1/packs/helix.bettermsgs.sha1")
                    ?.trim()?.takeIf { it.length == 40 }
                    ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            },
            1L,
            20L * 300,
        )
    }

    /**
     * Restores every borrowed inventory and shuts the GUI runtime down.
     */
    override fun onDisable() {
        if (::takeover.isInitialized) {
            takeover.restoreAll(Bukkit::getPlayer)
        }
        if (::scope.isInitialized) {
            igui?.let { runBlocking { it.shutdown() } }
            scope.cancel()
        }
    }

    /**
     * Handles `/msg [player] [message...]`.
     *
     * @param sender command sender.
     * @param command the command.
     * @param label used alias.
     * @param args player and optional message.
     * @return always `true`.
     */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        when {
            args.isEmpty() -> scope.launch { phoneGui?.open(player) }
            args.size == 1 -> openChat(player, args[0])
            else -> scope.launch(Dispatchers.IO) {
                val text = args.drop(1).joinToString(" ")
                client?.action("bettermsgs.send", player.name, args[0], text)
                withContext(mainDispatcher) {
                    player.sendMessage(
                        translations.component(player, "sent", "<gray>To <white>{target}</white>: {text}",
                            "target" to args[0], "text" to text),
                    )
                }
            }
        }
        return true
    }

    /**
     * Applies the addon resource pack and restores crash snapshots.
     *
     * @param event the join event.
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        takeover.restoreCrashed(event.player)
        val url = packUrl(event.player) ?: return
        logger.info("Sending resource pack to ${event.player.name}: $url (sha1 ${if (packSha1 != null) "yes" else "no"})")
        val sha1 = packSha1
        if (sha1 != null) {
            event.player.setResourcePack(url, sha1)
        } else {
            event.player.setResourcePack(url)
        }
    }

    /**
     * Logs the client's pack download outcome, for diagnosing unreachable
     * pack URLs.
     *
     * @param event the status event.
     */
    @EventHandler
    fun onPackStatus(event: org.bukkit.event.player.PlayerResourcePackStatusEvent) {
        logger.info("Resource pack status of ${event.player.name}: ${event.status}")
    }

    /**
     * Resolves the pack URL the player's CLIENT can reach: the configured
     * `bettermsgs.packurl` value, then the `HELIX_PACK_URL` env override,
     * then the address the player connected with (virtual host — control
     * URLs like `host.docker.internal` or `127.0.0.1` mean nothing to a
     * remote client), and finally the raw control URL.
     *
     * @param player the joining player.
     * @return a download URL, or `null` without a node connection.
     */
    private fun packUrl(player: Player): String? {
        val nodeClient = client ?: return null
        val controlPort = runCatching { java.net.URI(nodeClient.controlUrl).port }.getOrDefault(8080)
        // 0.0.0.0 is a bind address, never something a client can download from
        configuredPackUrl?.takeIf { it.isNotBlank() && !it.contains("0.0.0.0") }
            ?.let { return expandPackUrl(it, controlPort) }
        System.getenv("HELIX_PACK_URL")?.let { return expandPackUrl(it, controlPort) }
        val clientHost = player.virtualHost?.hostString
            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "127.0.0.1" }
        if (clientHost != null) {
            return expandPackUrl(clientHost, controlPort)
        }
        return nodeClient.controlUrl + PACK_PATH
    }

    /**
     * Expands operator input into a full download URL: a bare host or ip
     * gets the control port and the pack path appended, a base URL just
     * the path.
     *
     * @param value full URL, `host:port` or bare host/ip.
     * @param controlPort port of the control API.
     * @return a complete pack URL.
     */
    private fun expandPackUrl(value: String, controlPort: Int): String {
        val base = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            val hostPort = if (':' in value) value else "$value:$controlPort"
            "http://$hostPort"
        }
        return if (base.contains("/api/")) base else base.trimEnd('/') + PACK_PATH
    }

    /**
     * Restores the inventory when a player quits mid-chat.
     *
     * @param event the quit event.
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        chats.remove(event.player.uniqueId)
        pendingInput.remove(event.player.uniqueId)
        takeover.restore(event.player)
        clearFocus(event.player.name)
    }

    /**
     * Routes clicks in the borrowed player inventory (hotbar controls) —
     * IGui only owns the top chest inventory.
     *
     * @param event the click event (already cancelled by IGui).
     */
    @EventHandler
    fun onLowerClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val state = chats[player.uniqueId] ?: return
        if (event.rawSlot < 54 + 27) {
            return
        }
        when (event.rawSlot - 54 - 27) {
            0 -> scope.launch { phoneGui?.open(player) }
            3 -> scroll(player, state, ChatMath.WINDOW)
            4 -> startInput(player, state)
            5 -> scroll(player, state, -ChatMath.WINDOW)
            8 -> scope.launch { chatGui?.close(player) }
        }
    }

    /**
     * Consumes chat lines of players who are writing a message.
     *
     * @param event the async chat event.
     */
    @EventHandler
    fun onChat(event: io.papermc.paper.event.player.AsyncChatEvent) {
        val peer = pendingInput.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(event.message())
        scope.launch(Dispatchers.IO) {
            if (!text.equals("cancel", ignoreCase = true)) {
                client?.action("bettermsgs.send", event.player.name, peer, text)
            }
            withContext(mainDispatcher) { openChat(event.player, peer) }
        }
    }

    /**
     * Opens (or reopens) the chat with a peer.
     *
     * @param player the viewer.
     * @param peer conversation partner name.
     */
    fun openChat(player: Player, peer: String) {
        val state = ChatState(peer.lowercase())
        chats[player.uniqueId] = state
        scope.launch { chatGui?.open(player) }
    }

    private fun scroll(player: Player, state: ChatState, delta: Int) {
        state.offset = ChatMath.clampOffset(state.offset + delta, state.total)
        scope.launch { refreshChat(player) }
    }

    private suspend fun refreshChat(player: Player) {
        chatGui?.refresh(player)
        chats[player.uniqueId]?.let { renderLowerInventory(player, it) }
    }

    private fun startInput(player: Player, state: ChatState) {
        pendingInput[player.uniqueId] = state.peer
        scope.launch {
            chatGui?.close(player)
            player.sendMessage(
                translations.component(
                    player,
                    "prompt.message",
                    "<gray>Type your message in chat (or <white>cancel</white>):",
                ),
            )
        }
    }

    private fun pollOpenChats() {
        val nodeClient = client ?: return
        chats.forEach { (uuid, state) ->
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            val window = fetchHistory(nodeClient, player.name, state.peer, state.offset) ?: return@forEach
            if (window.total != state.total) {
                scope.launch { refreshChat(player) }
            }
        }
    }

    private fun fetchHistory(nodeClient: NodeClient, player: String, peer: String, offset: Int): HistoryWindow? =
        nodeClient.action("bettermsgs.history", player, peer, offset.toString(), ChatMath.WINDOW.toString())
            ?.let { runCatching { json.decodeFromString<HistoryWindow>(it) }.getOrNull() }

    private fun fetchContacts(nodeClient: NodeClient, player: Player): List<Contact> {
        val contacts = nodeClient.action("bettermsgs.contacts", player.name)
            ?.let { runCatching { json.decodeFromString<List<Contact>>(it) }.getOrNull() }
            ?: emptyList()
        val known = contacts.map { it.name }.toSet()
        val online = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId && it.name.lowercase() !in known }
            .map { Contact(name = it.name.lowercase(), online = true) }
        return contacts + online
    }

    private fun clearFocus(playerName: String) {
        scope.launch(Dispatchers.IO) { client?.action("bettermsgs.focus", playerName, "-") }
    }

    // ------------------------------------------------------------- phone --

    private suspend fun buildPhoneGui(installed: IGui): GuiDefinition = installed.gui("bettermsgs.phone") {
        rows = 6
        landingPage = "home"
        page("home") {
            title { _ ->
                moveTo(0)
                texture(installed.cachedTexture("bettermsgs.home"))
                toStart()
            }
            paginate<Contact>(chestSlot(2, 2) rectTo chestSlot(5, 8)) {
                previousSlot = chestSlot(6, 1)
                nextSlot = chestSlot(6, 9)
                source { player -> withContext(Dispatchers.IO) { client?.let { fetchContacts(it, player) } ?: emptyList() } }
                render { context, contact -> contactItem(context.player, contact) }
                onClick { context, contact -> openChat(context.player, contact.name) }
            }
            item(chestSlot(6, 5)) { context ->
                namedItem(Material.BARRIER, translations.text(context.player, "item.close", "<red>Close"))
            }
            onClick(chestSlot(6, 5)) { context -> context.close() }
        }
    }

    private fun contactItem(viewer: Player, contact: Contact): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD, contact.unread.coerceIn(1, 64))
        head.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = Bukkit.getOfflinePlayer(contact.name)
            val color = if (contact.online) "<aqua>" else "<gray>"
            meta.displayName(translations.render("$color${contact.name}"))
            val status = if (contact.online) {
                translations.text(viewer, "note.online", "<green>online")
            } else {
                translations.text(viewer, "note.offline", "<dark_gray>offline")
            }
            val lore = mutableListOf(translations.render(status))
            if (contact.unread > 0) {
                lore += translations.render("<yellow>✉ ${contact.unread}")
            }
            meta.lore(lore)
        }
        return head
    }

    // -------------------------------------------------------------- chat --

    private suspend fun buildChatGui(installed: IGui): GuiDefinition = installed.gui("bettermsgs.chat") {
        rows = 6
        landingPage = "chat"
        page("chat") {
            prepare { player ->
                val state = chats[player.uniqueId] ?: return@prepare
                withContext(Dispatchers.IO) {
                    client?.let { nodeClient ->
                        fetchHistory(nodeClient, player.name, state.peer, state.offset)?.let { window ->
                            state.total = window.total
                            state.offset = ChatMath.clampOffset(state.offset, window.total)
                            state.messages = window.messages
                        }
                        nodeClient.action("bettermsgs.focus", player.name, state.peer)
                    }
                }
            }
            title { context ->
                moveTo(0)
                texture(installed.cachedTexture("bettermsgs.chat"))
                toStart()
                val state = chats[context.player.uniqueId]
                if (state != null) {
                    moveTo(169)
                    texture(installed.cachedTexture("bettermsgs.thumb${ChatMath.thumbIndex(state.offset, state.total)}"))
                    toStart()
                }
            }
            // header: back, peer head, scroll older
            item(chestSlot(1, 1)) { context ->
                namedItem(Material.ARROW, translations.text(context.player, "item.back", "<gray>Back"))
            }
            onClick(chestSlot(1, 1)) { context ->
                chats.remove(context.player.uniqueId)
                phoneGui?.open(context.player)
            }
            item(chestSlot(1, 5)) { context ->
                chats[context.player.uniqueId]?.let { state ->
                    val head = ItemStack(Material.PLAYER_HEAD)
                    head.editMeta(SkullMeta::class.java) { meta ->
                        meta.owningPlayer = Bukkit.getOfflinePlayer(state.peer)
                        meta.displayName(translations.render("<white>${state.peer}"))
                    }
                    head
                }
            }
            item(chestSlot(1, 9)) { context ->
                namedItem(Material.SPECTRAL_ARROW, translations.text(context.player, "item.scroll.up", "<gray>Older"))
            }
            onClick(chestSlot(1, 9)) { context ->
                chats[context.player.uniqueId]?.let { scroll(context.player, it, ChatMath.WINDOW) }
            }
            // messages: chest rows 2..6 show window indices 0..4
            for (row in 2..6) {
                for (column in 1..9) {
                    item(chestSlot(row, column)) { context ->
                        chats[context.player.uniqueId]?.let { state ->
                            messageItem(context.player, state, row - 2, column)
                        }
                    }
                }
            }
            onOpen { player ->
                chats[player.uniqueId]?.let { state ->
                    takeover.begin(player)
                    renderLowerInventory(player, state)
                }
            }
        }
        onClose { context ->
            takeover.restore(context.player)
            chats.remove(context.player.uniqueId)
            // keep the pending-input flow alive: the GUI closes for typing
            if (!pendingInput.containsKey(context.player.uniqueId)) {
                clearFocus(context.player.name)
            }
        }
    }

    private fun messageItem(viewer: Player, state: ChatState, rowIndex: Int, column: Int): ItemStack? {
        val message = windowMessage(state, rowIndex) ?: return emptyNote(viewer, state, rowIndex, column)
        val own = message.from.equals(viewer.name, ignoreCase = true)
        val headColumn = if (own) 9 else 1
        val textColumn = if (own) 8 else 2
        return when (column) {
            headColumn -> ItemStack(Material.PLAYER_HEAD).also { head ->
                head.editMeta(SkullMeta::class.java) { meta ->
                    meta.owningPlayer = Bukkit.getOfflinePlayer(message.from)
                    meta.displayName(translations.render(if (own) "<aqua>${message.from}" else "<white>${message.from}"))
                }
            }
            textColumn -> textItem(message, own)
            else -> null
        }
    }

    private fun windowMessage(state: ChatState, rowIndex: Int): ChatMessage? {
        // bottom-aligned: the newest message sits on the lowest visible row
        val padding = ChatMath.WINDOW - state.messages.size
        val index = rowIndex - padding
        return state.messages.getOrNull(index)
    }

    private fun emptyNote(viewer: Player, state: ChatState, rowIndex: Int, column: Int): ItemStack? {
        if (state.messages.isNotEmpty() || rowIndex != 2 || column != 5) {
            return null
        }
        return namedItem(
            Material.PAPER,
            translations.text(viewer, "note.empty", "<gray>No messages yet — say hi!"),
        )
    }

    private fun textItem(message: ChatMessage, own: Boolean): ItemStack {
        val time = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(message.epochMs))
        val lines = ChatMath.wrap(message.text)
        val color = if (own) "<aqua>" else "<white>"
        val item = ItemStack(Material.PAPER)
        item.editMeta { meta ->
            meta.displayName(translations.render("<dark_gray>[$time] $color${lines.first()}"))
            if (lines.size > 1) {
                meta.lore(lines.drop(1).map { translations.render("$color$it") })
            }
        }
        return item
    }

    /**
     * Fills the borrowed player inventory: window rows 5..7 as message rows
     * plus the hotbar control bar.
     *
     * @param player the viewer.
     * @param state the open chat.
     */
    private fun renderLowerInventory(player: Player, state: ChatState) {
        if (!takeover.active(player)) {
            return
        }
        val inventory = player.inventory
        inventory.clear()
        for (rowIndex in 5..7) {
            val base = 9 + (rowIndex - 5) * 9
            for (column in 1..9) {
                inventory.setItem(base + column - 1, messageItem(player, state, rowIndex, column))
            }
        }
        inventory.setItem(0, namedItem(Material.ARROW, translations.text(player, "item.back", "<gray>Back")))
        inventory.setItem(3, namedItem(Material.SPECTRAL_ARROW, translations.text(player, "item.scroll.up", "<gray>Older")))
        inventory.setItem(4, namedItem(Material.WRITABLE_BOOK, translations.text(player, "item.write", "<green>Write a message…")))
        inventory.setItem(5, namedItem(Material.SPECTRAL_ARROW, translations.text(player, "item.scroll.down", "<gray>Newer")))
        inventory.setItem(8, namedItem(Material.BARRIER, translations.text(player, "item.close", "<red>Close")))
    }

    private fun namedItem(material: Material, name: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta -> meta.displayName(translations.render(name)) }
        return item
    }

    private fun namedItem(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta -> meta.displayName(name) }
        return item
    }

    private companion object {
        /** Download path of the BetterMSGs resource pack on the control API. */
        const val PACK_PATH = "/api/v1/packs/helix.bettermsgs.zip"
    }
}
