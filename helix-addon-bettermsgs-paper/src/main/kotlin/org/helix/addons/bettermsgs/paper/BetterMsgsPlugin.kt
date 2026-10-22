package org.helix.addons.bettermsgs.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.screen.ButtonStyle
import de.tytoss.igui.screen.LedgerBadge
import de.tytoss.igui.screen.LedgerButton
import de.tytoss.igui.screen.LedgerEmpty
import de.tytoss.igui.screen.LedgerLayout
import de.tytoss.igui.screen.LedgerLine
import de.tytoss.igui.screen.LedgerPill
import de.tytoss.igui.screen.LedgerRow
import de.tytoss.igui.screen.LedgerScreen
import de.tytoss.igui.screen.LedgerView
import de.tytoss.igui.screen.LifeSign
import de.tytoss.igui.screen.PillColor
import de.tytoss.igui.screen.Villagers
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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
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
 * One contact of the letters list.
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
 * BetterMSGs Paper component: `/msg` opens a medieval letters ledger (the
 * shared [LedgerScreen]) — the contact list, and per contact the
 * correspondence with paging and a "write" button. Conversations
 * live on the Helix node (`helix.bettermsgs` addon actions), so messaging
 * works across every server of the network.
 */
class BetterMsgsPlugin : org.bukkit.plugin.java.JavaPlugin(), Listener {
    private val json = Json { ignoreUnknownKeys = true }
    private val chats = ConcurrentHashMap<UUID, ChatState>()
    private val pendingInput = ConcurrentHashMap<UUID, String>()
    private var igui: IGui? = null
    private var lettersGui: GuiDefinition? = null
    private val ledger = LedgerScreen()
    private val contacts = ConcurrentHashMap<UUID, List<Contact>>()
    private val contactPages = ConcurrentHashMap<UUID, Int>()
    private var client: NodeClient? = null
    private lateinit var translations: Translations
    private lateinit var takeover: InventoryTakeover
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
     * Boots the GUI runtime: IGui install, listeners,
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
            igui = installed
            lettersGui = buildLettersGui(installed)
            logger.info("BetterMSGs GUIs ready")
        }
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 20L, 100L)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollOpenChats() }, 20L, 20L)
    }

    /**
     * Restores borrowed inventories of older versions and cancels this plugin's coroutine
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
        client?.close()
        client = null
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
            args.isEmpty() -> {
                chats.remove(player.uniqueId)
                scope.launch { lettersGui?.open(player) }
            }
            args.size == 1 -> openChat(player, args[0])
            else -> scope.launch(Dispatchers.IO) {
                val text = args.drop(1).joinToString(" ")
                client?.action("bettermsgs.send", player.name, args[0], text)
                withContext(mainDispatcher) {
                    player.sendMessage(
                        translations.chatComponent(player, "sent", "<tc:text>To <tc:accent>{target}</tc:accent><tc:muted>:</tc:muted> {text}",
                            "target" to args[0], "text" to text),
                    )
                }
            }
        }
        return true
    }

    /**
     * Restores crash snapshots of inventories borrowed by older versions
     * (the chat used to take the player inventory over).
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
        ledger.forget(event.player)
        contacts.remove(event.player.uniqueId)
        contactPages.remove(event.player.uniqueId)
        takeover.restore(event.player)
        clearFocus(event.player.name)
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
        chats[player.uniqueId] = ChatState(peer.lowercase())
        scope.launch { lettersGui?.open(player) }
    }

    private fun startInput(player: Player, state: ChatState) {
        pendingInput[player.uniqueId] = state.peer
        scope.launch {
            lettersGui?.close(player)
            player.sendMessage(
                translations.chatComponent(
                    player,
                    "prompt.message",
                    "<tc:text>Type your message in chat (or <tc:accent>cancel</tc:accent>):",
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
                scope.launch { lettersGui?.refresh(player) }
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

    // ------------------------------------------------------------ letters --

    private suspend fun buildLettersGui(installed: IGui): GuiDefinition = installed.gui("bettermsgs.letters") {
        rows = 6
        playerInventory = true
        landingPage = "main"
        page("main") {
            prepare { player -> showLedger(player) }
            ledger.install(
                this,
                onRow = { ctx, row ->
                    if (chats.containsKey(ctx.player.uniqueId)) return@install
                    val index = (contactPages[ctx.player.uniqueId] ?: 0) * LedgerLayout.ROWS + row
                    val contact = contacts[ctx.player.uniqueId]?.getOrNull(index) ?: return@install
                    chats[ctx.player.uniqueId] = ChatState(contact.name)
                    ctx.gui.refresh(ctx.player)
                },
                onButton = { ctx, button ->
                    val state = chats[ctx.player.uniqueId] ?: return@install
                    if (button == 1) startInput(ctx.player, state)
                },
                onPage = { ctx, step ->
                    val state = chats[ctx.player.uniqueId]
                    if (state == null) {
                        contactPages.merge(ctx.player.uniqueId, step, Int::plus)
                    } else {
                        // left = older letters (further back), right = newer
                        state.offset = ChatMath.clampOffset(state.offset - step * ChatMath.WINDOW, state.total)
                    }
                    ctx.gui.refresh(ctx.player)
                },
                onBack = { ctx ->
                    if (chats.remove(ctx.player.uniqueId) != null) {
                        clearFocus(ctx.player.name)
                        ctx.gui.refresh(ctx.player)
                    }
                },
            )
        }
        onClose { ctx ->
            ledger.forget(ctx.player)
            contacts.remove(ctx.player.uniqueId)
            contactPages.remove(ctx.player.uniqueId)
            chats.remove(ctx.player.uniqueId)
            // keep the pending-input flow alive: the GUI closes for typing
            if (!pendingInput.containsKey(ctx.player.uniqueId)) {
                clearFocus(ctx.player.name)
            }
        }
    }

    /** Fetches what the player looks at (contacts or one correspondence) and puts it into the ledger. */
    private suspend fun showLedger(player: Player) {
        val nodeClient = client ?: return
        val state = chats[player.uniqueId]
        if (state == null) {
            val list = withContext(Dispatchers.IO) { fetchContacts(nodeClient, player) }
            contacts[player.uniqueId] = list
            ledger.show(player, contactsView(player, list))
        } else {
            withContext(Dispatchers.IO) {
                fetchHistory(nodeClient, player.name, state.peer, state.offset)?.let { window ->
                    state.total = window.total
                    state.offset = ChatMath.clampOffset(state.offset, window.total)
                    state.messages = window.messages
                }
                nodeClient.action("bettermsgs.focus", player.name, state.peer)
            }
            ledger.show(player, chatView(player, state))
        }
    }

    /** The contact list: unread contacts with a red pill and a seal, others with their online status. */
    private fun contactsView(player: Player, list: List<Contact>): LedgerView {
        val pageCount = maxOf(1, (list.size + LedgerLayout.ROWS - 1) / LedgerLayout.ROWS)
        val page = (contactPages[player.uniqueId] ?: 0).coerceIn(0, pageCount - 1)
            .also { contactPages[player.uniqueId] = it }
        val rows = list.drop(page * LedgerLayout.ROWS).take(LedgerLayout.ROWS).map { contact ->
            val status = screen(player, if (contact.online) "note.online" else "note.offline")
            val pill = when {
                contact.unread > 0 -> LedgerPill(screen(player, "screen.unread", "count" to contact.unread.toString()), PillColor.RED)
                contact.online -> LedgerPill(status, PillColor.GREEN)
                else -> LedgerPill(status, PillColor.GREY)
            }
            LedgerRow(
                label = contact.name,
                icon = head(contact.name),
                badge = if (contact.unread > 0) LedgerBadge.SEAL else null,
                pill = pill,
                tooltip = listOfNotNull(
                    Component.text(contact.name, GOLD),
                    Component.text(screen(player, "screen.kind.contact"), GREY),
                    Component.text(status, if (contact.online) GREEN else GREY),
                    contact.unread.takeIf { it > 0 }
                        ?.let { Component.text(screen(player, "screen.unread", "count" to it.toString()), ERROR) },
                    contact.lastEpochMs.takeIf { it > 0 }
                        ?.let { Component.text(screen(player, "screen.last", "time" to dateOf(it)), PARCHMENT) },
                    Component.text(screen(player, "screen.hint.open"), GOLD),
                ),
            )
        }
        val unread = list.sumOf { it.unread }
        return LedgerView(
            title = screen(player, "screen.title"),
            subtitle = screen(player, "screen.contacts.subtitle"),
            rows = rows,
            page = page,
            pages = pageCount,
            detail = buildList {
                add(LedgerLine(screen(player, "screen.post"), LedgerScreen.RED))
                add(LedgerLine(""))
                add(LedgerLine(player.name))
                add(LedgerLine(screen(player, "screen.contacts.count", "count" to list.size.toString()), LedgerScreen.FADED))
                add(
                    LedgerLine(
                        screen(player, "screen.unread.total", "count" to unread.toString()),
                        if (unread > 0) LedgerScreen.RED else LedgerScreen.FADED,
                    ),
                )
                if (list.isNotEmpty()) {
                    add(LedgerLine(""))
                    addAll(help(player, "contacts", 3))
                }
            },
            lifeSign = LifeSign.QUILL,
            empty = LedgerEmpty(listOf(screen(player, "screen.empty.contacts")), Villagers.NARRATOR),
        )
    }

    /** One correspondence: eight letters per page (arrows scroll older/newer), back to the contacts, write a letter. */
    private fun chatView(player: Player, state: ChatState): LedgerView {
        val rows = state.messages.takeLast(LedgerLayout.ROWS).map { message ->
            val own = message.from.equals(player.name, ignoreCase = true)
            LedgerRow(
                label = message.text,
                value = timeOf(message.epochMs),
                labelColor = if (own) OWN_INK else LedgerScreen.INK,
                valueColor = LedgerScreen.FADED,
                icon = head(message.from),
                tooltip = buildList {
                    add(Component.text(message.from, GOLD))
                    add(Component.text(screen(player, "screen.kind.letter", "time" to dateOf(message.epochMs)), GREY))
                    ChatMath.wrap(message.text).forEach { add(Component.text(it, PARCHMENT)) }
                },
            )
        }
        val (page, pages) = ChatMath.pages(state.offset, state.total)
        val online = Bukkit.getPlayerExact(state.peer) != null ||
            contacts[player.uniqueId]?.firstOrNull { it.name == state.peer }?.online == true
        return LedgerView(
            title = state.peer,
            subtitle = screen(player, "screen.chat.subtitle", "peer" to state.peer),
            rows = rows,
            page = page,
            pages = pages,
            detail = buildList {
                add(LedgerLine(screen(player, "screen.post"), LedgerScreen.RED))
                add(LedgerLine(""))
                add(LedgerLine(state.peer))
                add(LedgerLine(screen(player, if (online) "note.online" else "note.offline"), if (online) LedgerScreen.GREEN else LedgerScreen.FADED))
                add(LedgerLine(screen(player, "screen.letters", "count" to state.total.toString()), LedgerScreen.FADED))
                add(LedgerLine(""))
                addAll(help(player, "chat", 4))
            },
            back = true,
            rightButton = LedgerButton(screen(player, "screen.write"), ButtonStyle.PRIMARY),
            lifeSign = LifeSign.QUILL,
            empty = LedgerEmpty(listOf(screen(player, "screen.empty.chat")), Villagers.NARRATOR),
        )
    }

    private fun help(player: Player, mode: String, lines: Int): List<LedgerLine> =
        (1..lines).map { LedgerLine(screen(player, "screen.help.$mode.$it")) }

    private fun head(name: String): ItemStack = ItemStack(Material.PLAYER_HEAD).apply {
        editMeta(SkullMeta::class.java) { it.owningPlayer = Bukkit.getOfflinePlayer(name) }
    }

    /** Prefix-free text in the player's language, without formatting (the ledger colours it). */
    private fun screen(player: Player, key: String, vararg params: Pair<String, String>): String =
        PlainTextComponentSerializer.plainText().serialize(translations.render(translations.text(player, key, key, *params)))

    private fun timeOf(epochMs: Long): String = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

    private fun dateOf(epochMs: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

    /** Ink of the viewer's own letters and the tooltip token colours. */
    private companion object {
        val OWN_INK: TextColor = TextColor.color(0x1F3F6B)

        /** Tooltip names and actions (`gold` base). */
        val GOLD: TextColor = TextColor.color(0xFFCF4A)

        /** Tooltip categories and offline (`disabled` base). */
        val GREY: TextColor = TextColor.color(0xA89A84)

        /** Tooltip values (`parchment` base). */
        val PARCHMENT: TextColor = TextColor.color(0xF1E2C2)

        /** Online (`success` base). */
        val GREEN: TextColor = TextColor.color(0x4AC85E)

        /** Unread count (`error` base). */
        val ERROR: TextColor = TextColor.color(0xFF5A4A)
    }
}
