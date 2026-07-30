package org.helix.addons.bettermsgs.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.texture.GuiTextureDefinition
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
    private val skins = SkinPixels()
    private lateinit var scope: CoroutineScope

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
            val installed = awaitSharedIGui()
            val guiFont = Key.key("bettermsgs", "gui")
            installed.saveTexture(GuiTextureDefinition("bettermsgs.home", "\ue000", guiFont, 176, 222))
            installed.saveTexture(GuiTextureDefinition("bettermsgs.chat", "\ue001", guiFont, 176, 222))
            for (index in 0..7) {
                installed.saveTexture(
                    GuiTextureDefinition("bettermsgs.thumb$index", "${'\ue010' + index}", guiFont, 4, 20),
                )
            }
            igui = installed
            phoneGui = buildPhoneGui(installed)
            chatGui = buildChatGui(installed)
            logger.info("BetterMSGs GUIs ready")
        }
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 20L, 100L)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollOpenChats() }, 20L, 20L)
    }

    /**
     * Restores every borrowed inventory and cancels this plugin's coroutine
     * scope. Does not shut IGui itself down — it is the shared instance
     * every addon's menu uses, and only the owning Helix-GUIs plugin may do
     * that (on its own, later, onDisable).
     */
    override fun onDisable() {
        if (::takeover.isInitialized) {
            takeover.restoreAll(Bukkit::getPlayer)
        }
        if (::scope.isInitialized) {
            igui = null
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
     * Restores crash snapshots of borrowed inventories. The GUI textures
     * arrive with the network resource pack, which the node merges from all
     * addon packs and the Velocity bridge applies on proxy join.
     *
     * @param event the join event.
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        takeover.restoreCrashed(event.player)
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
                        skins.fetch(player.name)
                        skins.fetch(state.peer)
                        state.messages.map { it.from }.distinct().forEach(skins::fetch)
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
                    moveTo(28)
                    text(state.peer, 0, NamedTextColor.WHITE, rowFont(0))
                    toStart()
                    drawHead(this, state.peer, headRow = 0, x = 8)
                    if (state.messages.isEmpty()) {
                        centeredText(
                            translations.text(context.player, "note.empty", "No messages yet - say hi!"),
                            0,
                            88,
                            NamedTextColor.GRAY,
                            rowFont(4),
                        )
                    }
                    val padding = ChatMath.WINDOW - state.messages.size
                    state.messages.forEachIndexed { index, message ->
                        val row = padding + index + 1
                        val own = message.from.equals(context.player.name, ignoreCase = true)
                        val time = timeOf(message.epochMs)
                        val line = "[$time] ${ChatMath.ellipsize(message.text, 24)}"
                        if (own) {
                            endText(line, 0, 146, NamedTextColor.AQUA, rowFont(row))
                            drawHead(this, message.from, headRow = row, x = 150)
                        } else {
                            moveTo(28)
                            text(line, 0, NamedTextColor.WHITE, rowFont(row))
                            toStart()
                            drawHead(this, message.from, headRow = row, x = 8)
                        }
                    }
                }
            }
            // header: back, peer head, scroll older
            onClick(chestSlot(1, 1)) { context ->
                chats.remove(context.player.uniqueId)
                phoneGui?.open(context.player)
            }
            onClick(chestSlot(1, 9)) { context ->
                chats[context.player.uniqueId]?.let { scroll(context.player, it, ChatMath.WINDOW) }
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

    private fun timeOf(epochMs: Long): String = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

    /**
     * Draws a player's face as 8x8 colored pixel glyphs at a text row.
     *
     * @param display the title builder.
     * @param name skin owner.
     * @param headRow head anchor row (0 = header, 1..8 = message rows).
     * @param x left edge in GUI pixels.
     */
    private fun drawHead(display: de.tytoss.igui.display.DisplayBuilder, name: String, headRow: Int, x: Int) {
        val pixels = skins.cached(name) ?: return
        val font = Key.key("bettermsgs", "pixels")
        for (sub in 0..7) {
            val glyph = (0xE100 + headRow * 8 + sub).toChar().toString()
            // bitmap glyphs advance imageWidth + 1 (shadow column) — track 3,
            // then pull back 1 so the pixels sit seamlessly at 2 px
            display.characterWidth(glyph, 3)
            display.moveTo(x)
            for (px in 0..7) {
                val argb = pixels[sub * 8 + px]
                if (argb ushr 24 < 0x80) {
                    display.space(2)
                } else {
                    display.text(glyph, 0, net.kyori.adventure.text.format.TextColor.color(argb and 0xFFFFFF), font)
                    display.space(-1)
                }
            }
            display.toStart()
        }
    }

    private fun rowFont(row: Int): Key = Key.key("bettermsgs", "text_row_$row")

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
        // the chat is fully drawn — the borrowed inventory just stays empty
        player.inventory.clear()
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
}
