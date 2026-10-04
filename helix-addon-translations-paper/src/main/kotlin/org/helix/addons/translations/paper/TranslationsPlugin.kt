package org.helix.addons.translations.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenBook
import de.tytoss.igui.IGui
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputTimeoutException
import de.tytoss.igui.screen.LedgerLayout
import de.tytoss.igui.screen.LedgerLine
import de.tytoss.igui.screen.LedgerRow
import de.tytoss.igui.screen.LedgerScreen
import de.tytoss.igui.screen.LedgerView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta

/** Per-viewer state for the translations ledger. */
private class Session {
    @Volatile var view: TranslationsView = TranslationsView()

    @Volatile var language: String = "en"

    /** Owner group filter (null = all owners). */
    @Volatile var owner: String? = null

    /** Search query (blank = none). */
    @Volatile var query: String = ""

    /** List shows owner groups instead of keys. */
    @Volatile var showGroups: Boolean = true

    /** Key selected for preview/editing (null = none). */
    @Volatile var selectedKey: String? = null

    /** Current list page. */
    @Volatile var page: Int = 0

    /** Edited-but-unsaved value from the book editor (null = none). */
    @Volatile var pending: String? = null

    /** Whether the next delete click confirms. */
    @Volatile var confirmDelete: Boolean = false
}

/** One owner group in the browse list. */
private data class Group(val owner: String, val count: Int, val edited: Int)

/**
 * Translations editor Paper component: `/translationsmenu` opens the medieval
 * ledger ([LedgerScreen]) — owner groups, then keys, then one key's detail
 * page with a plain preview (coloured in the row tooltips) and the actions
 * edit/save/reset. Values are edited in a writable book (multiline, long);
 * search runs through a virtual anvil. Reads and writes travel to the node's
 * admin-gated `helix.translations.*` actions.
 */
class TranslationsPlugin : org.bukkit.plugin.java.JavaPlugin(), Listener {
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val bookEditing = ConcurrentHashMap.newKeySet<UUID>()
    private val bookReturn = ConcurrentHashMap<UUID, ItemStack>()
    private var igui: IGui? = null
    private var gui: GuiDefinition? = null
    private var client: NodeClient? = null
    private val ledger = LedgerScreen()
    private lateinit var scope: CoroutineScope

    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) server.scheduler.runTask(this@TranslationsPlugin, block)
        }
    }

    /** Boots the node client and builds the ledger GUI. */
    override fun onEnable() {
        val nodeClient = NodeClient.fromEnvironment()
        if (nodeClient == null) {
            logger.warning("No Helix environment found — translations editor disabled.")
            server.pluginManager.disablePlugin(this)
            return
        }
        client = nodeClient
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        server.pluginManager.registerEvents(this, this)
        scope.launch {
            val installed = de.tytoss.igui.awaitSharedIGui()
            igui = installed
            gui = buildGui(installed)
            logger.info("Translations editor (ledger) ready")
        }
    }

    /** Cancels the coroutine scope; restores any borrowed book hand items. */
    override fun onDisable() {
        bookReturn.keys.toList().forEach { uuid -> Bukkit.getPlayer(uuid)?.let { restoreBook(it) } }
        if (::scope.isInitialized) {
            igui = null
            scope.cancel()
        }
        client?.close()
        client = null
    }

    /**
     * Captures the writable-book editor result as the selected key's pending
     * value and reopens the editor.
     *
     * @param event the edit-book event.
     */
    @EventHandler
    fun onEditBook(event: PlayerEditBookEvent) {
        val player = event.player
        if (!bookEditing.remove(player.uniqueId)) return
        event.isSigning = false
        restoreBook(player)
        session(player).pending = pagesToText(event.newBookMeta)
        scope.launch { gui?.open(player, "main") }
    }

    /** Restores a book hand item if the player quit mid-edit. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        bookEditing.remove(event.player.uniqueId)
        restoreBook(event.player)
    }

    /**
     * Handles `/translationsmenu`.
     *
     * @param sender command sender.
     * @param command the command.
     * @param label used alias.
     * @param args ignored.
     * @return always `true`.
     */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only players can open the translations editor.")
            return true
        }
        if (!player.hasPermission("helix.admin")) {
            player.sendMessage(MiniPreview.render("<#ff5a4a>You do not have permission to edit translations."))
            return true
        }
        restoreBook(player) // recover a stray book from an aborted edit
        bookEditing.remove(player.uniqueId)
        val ready = gui ?: run {
            player.sendMessage(MiniPreview.render("<#ffb43a>Warning: The translations editor is still starting up — try again in a moment."))
            return true
        }
        scope.launch {
            val s = session(player)
            s.showGroups = true
            s.owner = null
            s.query = ""
            s.selectedKey = null
            s.pending = null
            s.page = 0
            loadView(player, s)
            ready.open(player, "main")
        }
        return true
    }

    private fun session(player: Player): Session = sessions.getOrPut(player.uniqueId) { Session() }

    private suspend fun loadView(player: Player, session: Session) {
        val view = withContext(Dispatchers.IO) { client?.view(player.name) } ?: return
        session.view = view
        if (session.language !in view.languages) session.language = view.defaultLanguage
    }

    // ---------------------------------------------------------------- gui --

    private suspend fun buildGui(installed: IGui): GuiDefinition = installed.gui("translations") {
        rows = 6
        playerInventory = true
        landingPage = "main"
        page("main") {
            permission = "helix.admin"
            prepare { player ->
                val s = session(player)
                loadView(player, s)
                ledger.show(player, viewOf(s))
            }
            ledger.install(
                this,
                onRow = { ctx, row -> onRowClick(ctx.player, row) },
                onButton = { ctx, button -> onButtonClick(ctx.player, ctx, button) },
                onPage = { ctx, step ->
                    session(ctx.player).page += step
                    refresh(ctx.player)
                },
            )
        }
        onClose { ctx ->
            session(ctx.player).confirmDelete = false
            ledger.forget(ctx.player)
        }
    }

    private fun selectable(session: Session): Boolean = session.selectedKey != null && !session.showGroups

    private fun displayValue(session: Session): String = session.pending ?: currentValue(session)

    /** Builds the ledger page for the session's mode: owner groups, keys, or one key's detail. */
    private fun viewOf(s: Session): LedgerView {
        val language = s.language.uppercase()
        val buttons = listOf(if (s.showGroups) "Search" else "Back", "Lang: $language")
        if (selectable(s)) {
            val key = s.selectedKey!!.removePrefix("helix.translations.")
            return LedgerView(
                title = "Translations",
                subtitle = ellipsize(key, 40),
                rows = detailRows(s),
                left = info(s, "Key") + listOf(
                    LedgerLine(statusOf(s).removePrefix("· ").ifEmpty { "-" }, LedgerScreen.FADED),
                    LedgerLine(if (s.pending != null) "unsaved changes" else "", LedgerScreen.RED),
                ),
                right = help("Click the preview", "to see colours.", "Edit opens a book;", "then save it."),
                buttons = buttons,
                quill = true,
            )
        }
        val size = listSize(s)
        val pages = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)
        s.page = s.page.coerceIn(0, pages - 1)
        val rows = if (s.showGroups) {
            pageWindow(groups(s), s.page).map { group ->
                LedgerRow(
                    label = group.owner,
                    value = "${group.edited}/${group.count}",
                    valueColor = if (group.edited > 0) LedgerScreen.GREEN else LedgerScreen.FADED,
                    tooltip = listOf(
                        Component.text(group.owner, TextColor.color(0xFFCF4A)),
                        Component.text("${group.count} keys, ${group.edited} edited", NamedTextColor.GRAY),
                    ),
                )
            }
        } else {
            pageWindow(filteredEntries(s), s.page).map { keyRow(it, s) }
        }
        val subtitle = when {
            s.showGroups -> "$size groups"
            s.query.isNotBlank() -> "Search: ${s.query}"
            else -> s.owner ?: "All keys"
        }
        return LedgerView(
            title = "Translations",
            subtitle = subtitle,
            rows = rows,
            page = s.page,
            pages = pages,
            left = info(s, if (s.showGroups) "Groups" else "Keys"),
            right = if (s.showGroups) {
                help("Pick a group", "to list its keys.", "Search finds keys", "and values.")
            } else {
                help("Pick a key", "to preview and", "edit its value.", "Back: groups.")
            },
            buttons = buttons,
        )
    }

    private fun keyRow(entry: TranslationEntry, s: Session): LedgerRow {
        val (status, color) = when {
            entry.values.containsKey(s.language) -> "edited" to LedgerScreen.GREEN
            entry.defaults[s.language] == null -> "missing" to LedgerScreen.RED
            else -> "default" to LedgerScreen.FADED
        }
        val effective = entry.values[s.language]
            ?: entry.defaults[s.language]
            ?: entry.defaults[s.view.defaultLanguage]
            ?: ""
        return LedgerRow(
            label = entry.key.removePrefix("helix.translations.").let { if (s.owner != null) it.removePrefix("${s.owner}.") else it },
            value = status,
            valueColor = color,
            tooltip = listOf(
                Component.text(entry.key.removePrefix("helix.translations."), TextColor.color(0xFFCF4A)),
                MiniPreview.render(if (effective.isEmpty()) "<#a89a84>(empty)" else effective),
            ),
        )
    }

    /** Preview lines (rows 0..4) and the actions edit/save/reset (rows 5..7). */
    private fun detailRows(s: Session): List<LedgerRow> {
        val value = displayValue(s)
        val rendered = value.split("\n").map { MiniPreview.render(it) }
        val tooltip = listOf(Component.text("Preview (${s.language})", TextColor.color(0xFFCF4A))) + rendered
        val lines = MiniPreview.linesOf(value).map { runs -> runs.joinToString("") { it.text } }
        val preview = (0 until PREVIEW_ROWS).map { i ->
            val text = when {
                value.isEmpty() && i == 0 -> "(empty)"
                i == PREVIEW_ROWS - 1 && lines.size > PREVIEW_ROWS -> "..."
                else -> lines.getOrNull(i) ?: ""
            }
            LedgerRow(label = text, labelColor = if (value.isEmpty()) LedgerScreen.FADED else LedgerScreen.INK, tooltip = tooltip)
        }
        val canSave = s.pending != null
        return preview + listOf(
            LedgerRow("Edit in book", "multiline", LedgerScreen.INK, LedgerScreen.FADED, ItemStack.of(Material.WRITABLE_BOOK)),
            LedgerRow(
                "Save",
                if (canSave) "unsaved" else "",
                if (canSave) LedgerScreen.GREEN else LedgerScreen.FADED,
                LedgerScreen.RED,
                ItemStack.of(if (canSave) Material.LIME_DYE else Material.GRAY_DYE),
            ),
            LedgerRow("Reset to default", "", LedgerScreen.RED, icon = ItemStack.of(Material.WATER_BUCKET)),
        )
    }

    private fun info(s: Session, mode: String): List<LedgerLine> = listOf(
        LedgerLine("Scriptorium", LedgerScreen.RED),
        LedgerLine(""),
        LedgerLine(mode),
        LedgerLine("Language: ${s.language}", LedgerScreen.FADED),
        LedgerLine("${s.view.entries.size} keys", LedgerScreen.FADED),
        LedgerLine("${s.view.entries.count { it.values.containsKey(s.language) }} edited", LedgerScreen.FADED),
    )

    private fun help(vararg lines: String): List<LedgerLine> =
        listOf(LedgerLine("Guide", LedgerScreen.RED), LedgerLine("")) + lines.map { LedgerLine(it) }

    private fun statusOf(session: Session): String {
        val entry = entryOf(session) ?: return ""
        return when {
            entry.values.containsKey(session.language) -> "· edited"
            entry.defaults[session.language] == null -> "· no value"
            else -> "· default"
        }
    }

    // ------------------------------------------------------------ actions --

    private fun onRowClick(player: Player, index: Int) {
        val s = session(player)
        when {
            selectable(s) -> when (index) {
                PREVIEW_ROWS -> openBook(player)
                PREVIEW_ROWS + 1 -> saveCurrent(player)
                PREVIEW_ROWS + 2 -> resetCurrent(player)
            }
            s.showGroups -> {
                val group = pageWindow(groups(s), s.page).getOrNull(index) ?: return
                s.owner = group.owner
                s.showGroups = false
                s.page = 0
                refresh(player)
            }
            else -> {
                val entry = pageWindow(filteredEntries(s), s.page).getOrNull(index) ?: return
                s.selectedKey = entry.key
                s.pending = null
                s.confirmDelete = false
                refresh(player)
            }
        }
    }

    private fun onButtonClick(player: Player, ctx: GuiClickContext, b: Int) {
        val s = session(player)
        when {
            b == 1 -> cycleLanguage(player)
            s.showGroups -> search(player, ctx)
            selectable(s) -> { s.selectedKey = null; s.pending = null; refresh(player) }
            else -> { s.showGroups = true; s.owner = null; s.query = ""; s.page = 0; refresh(player) }
        }
    }

    private fun refresh(player: Player) {
        scope.launch { gui?.refresh(player) }
    }

    private fun search(player: Player, ctx: GuiClickContext) {
        scope.launch {
            val query = try {
                ctx.anvilInput(title = Component.text("Search translations"))
            } catch (ignored: GuiInputCancelledException) {
                return@launch
            } catch (ignored: GuiInputTimeoutException) {
                return@launch
            }
            val s = session(player)
            s.query = query.trim()
            s.showGroups = false
            s.owner = null
            s.selectedKey = null
            s.page = 0
            gui?.open(player, "main")
        }
    }

    // ---------------------------------------------------------- book edit --

    private fun openBook(player: Player) {
        val s = session(player)
        if (!selectable(s)) return
        val ready = gui ?: return
        val initial = displayValue(s)
        bookReturn[player.uniqueId] = player.inventory.itemInMainHand.clone()
        bookEditing.add(player.uniqueId)
        scope.launch {
            ready.close(player)
            val book = ItemStack.of(Material.WRITABLE_BOOK)
            book.editMeta(BookMeta::class.java) { meta ->
                textToPages(initial).forEach { meta.addPages(Component.text(it)) }
            }
            player.inventory.setItemInMainHand(book)
            PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerOpenBook(InteractionHand.MAIN_HAND))
        }
    }

    private fun restoreBook(player: Player) {
        bookReturn.remove(player.uniqueId)?.let { player.inventory.setItemInMainHand(it) }
    }

    private fun pagesToText(meta: BookMeta): String =
        (1..meta.pageCount).joinToString("\n") { meta.getPage(it) }.trimEnd('\n')

    private fun textToPages(text: String): List<String> =
        if (text.isEmpty()) listOf("") else text.chunked(BOOK_PAGE_CHARS)

    private fun saveCurrent(player: Player) {
        val s = session(player)
        val key = s.selectedKey ?: return
        if (!selectable(s)) return
        val value = s.pending ?: return
        val nodeClient = client ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { nodeClient.set(player.name, key, s.language, value) }
            feedback(player, ok, "Saved ${key.removePrefix("helix.translations.")} (${s.language})", "Save failed")
            if (ok) s.pending = null
            loadView(player, s)
            gui?.refresh(player)
        }
    }

    private fun resetCurrent(player: Player) {
        val s = session(player)
        val key = s.selectedKey ?: return
        if (!selectable(s)) return
        val nodeClient = client ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { nodeClient.reset(player.name, key, s.language) }
            feedback(player, ok, "Reset ${key.removePrefix("helix.translations.")} (${s.language})", "Nothing to reset")
            s.pending = null
            loadView(player, s)
            gui?.refresh(player)
        }
    }

    private fun cycleLanguage(player: Player) {
        val s = session(player)
        val languages = s.view.languages
        if (languages.isNotEmpty()) {
            val index = languages.indexOf(s.language).coerceAtLeast(0)
            s.language = languages[(index + 1) % languages.size]
        }
        s.pending = null
        refresh(player)
    }

    // ------------------------------------------------------------- helpers --

    private fun entryOf(session: Session): TranslationEntry? =
        session.view.entries.find { it.key == session.selectedKey }

    private fun currentValue(session: Session): String {
        val entry = entryOf(session) ?: return ""
        return entry.values[session.language]
            ?: entry.defaults[session.language]
            ?: entry.defaults[session.view.defaultLanguage]
            ?: ""
    }

    private fun filteredEntries(session: Session): List<TranslationEntry> {
        val all = session.view.entries
        val query = session.query.trim().lowercase()
        val lang = session.language
        val filtered = when {
            query.isNotBlank() -> all.filter { e ->
                e.key.lowercase().contains(query) ||
                    (e.values[lang] ?: e.defaults[lang] ?: "").lowercase().contains(query)
            }
            session.owner != null -> all.filter { it.owner == session.owner }
            else -> all
        }
        return filtered.sortedBy { it.key }
    }

    private fun groups(session: Session): List<Group> =
        session.view.entries.groupBy { it.owner }
            .map { (owner, list) -> Group(owner, list.size, list.count { it.values.containsKey(session.language) }) }
            .sortedBy { it.owner }

    private fun listSize(session: Session): Int =
        if (session.showGroups) groups(session).size else filteredEntries(session).size

    private fun <T> pageWindow(items: List<T>, page: Int): List<T> {
        val from = (page * PAGE_SIZE).coerceIn(0, items.size)
        val to = (from + PAGE_SIZE).coerceAtMost(items.size)
        return items.subList(from, to)
    }

    private fun feedback(player: Player, ok: Boolean, success: String, failure: String) {
        player.sendMessage(MiniPreview.render(if (ok) "<#4ac85e>$success" else "<#ff5a4a>$failure"))
    }

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take((max - 3).coerceAtLeast(0)) + "..."

    private companion object {
        /** Keys or groups per ledger page. */
        const val PAGE_SIZE = LedgerLayout.ROWS

        /** Ledger rows used for the value preview on the detail page. */
        const val PREVIEW_ROWS = 5

        /** Characters per writable-book page. */
        const val BOOK_PAGE_CHARS = 250
    }
}
