package org.helix.addons.translations.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenBook
import de.tytoss.igui.IGui
import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputTimeoutException
import de.tytoss.igui.texture.GuiTextureDefinition
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
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta

/** Per-viewer state for the chest translations editor. */
private class Session {
    @Volatile var view: TranslationsView = TranslationsView()

    @Volatile var language: String = "en"

    /** Owner group filter (null = all owners). */
    @Volatile var owner: String? = null

    /** Search query (blank = none). */
    @Volatile var query: String = ""

    /** Grid shows owner groups instead of keys. */
    @Volatile var showGroups: Boolean = true

    /** Key selected for preview/editing (null = none). */
    @Volatile var selectedKey: String? = null

    /** Current grid page. */
    @Volatile var page: Int = 0

    /** Edited-but-unsaved value from the book editor (null = none). */
    @Volatile var pending: String? = null

    /** Whether the next delete click confirms. */
    @Volatile var confirmDelete: Boolean = false
}

/** One owner group in the browse grid. */
private data class Group(val owner: String, val count: Int, val edited: Int)

/**
 * Translations editor Paper component: `/translationsmenu` opens a 6-row chest
 * with a full-window custom background (IGuard/BetterMSGs style). Chest rows
 * 0-1 are an embedded MiniMessage preview panel, rows 2-4 the key list, row 5
 * the action buttons. Values are edited in a writable book (multiline, long);
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
    private lateinit var scope: CoroutineScope

    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) server.scheduler.runTask(this@TranslationsPlugin, block)
        }
    }

    /** Boots the node client, installs the background texture and builds the chest GUI. */
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
            installed.saveTexture(
                GuiTextureDefinition("translations.bg", GLYPH, Key.key("translations", "ui"), 176, 222, 177),
            )
            igui = installed
            gui = buildGui(installed)
            logger.info("Translations editor (chest) ready")
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
            player.sendMessage(MiniPreview.render("<red>You do not have permission to edit translations."))
            return true
        }
        restoreBook(player) // recover a stray book from an aborted edit
        bookEditing.remove(player.uniqueId)
        val ready = gui ?: run {
            player.sendMessage(MiniPreview.render("<gray>Translations editor is still starting up…"))
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
        landingPage = "main"
        page("main") {
            permission = "helix.admin"
            cancelAllInteractions = true
            prepare { player -> loadView(player, session(player)) }
            title { ctx -> drawTitle(this, ctx.player) }
            for (i in 0 until LIST_COUNT) {
                val slot = LIST_START + i
                item(slot) { ctx -> listItem(session(ctx.player), i) }
                onClick(slot) { ctx -> onListClick(ctx.player, i) }
            }
            for (b in 0 until BUTTON_COUNT) {
                val slot = BUTTON_ROW + b
                item(slot) { ctx -> buttonItem(session(ctx.player), b) }
                onClick(slot) { ctx -> onButtonClick(ctx.player, ctx, b) }
            }
        }
        onClose { ctx -> session(ctx.player).confirmDelete = false }
    }

    private fun selectable(session: Session): Boolean = session.selectedKey != null && !session.showGroups

    private fun displayValue(session: Session): String = session.pending ?: currentValue(session)

    private fun drawTitle(display: DisplayBuilder, player: Player) {
        val s = session(player)
        igui?.cachedTexture("translations.bg")?.let { bg ->
            display.centeredTexture(bg)
            display.toStart()
        }
        val header = when {
            s.showGroups -> "Translations · ${s.language}"
            s.selectedKey != null -> {
                val mark = if (s.pending != null) " ·unsaved" else " ${statusOf(s)}"
                ellipsize(s.selectedKey!!.removePrefix("helix.translations."), 20) + " ·" + s.language + mark
            }
            else -> "${s.owner ?: "Keys"} · ${s.language}"
        }
        display.centeredText(ellipsize(header, 34), 0, color = NamedTextColor.WHITE)
        if (selectable(s)) {
            val value = displayValue(s)
            if (value.isEmpty()) line(display, "(empty)", 1, NamedTextColor.GRAY)
            else drawPreview(display, value, listOf(1, 2), PREVIEW_MAX_CHARS)
        } else {
            line(display, if (s.query.isNotBlank()) "search: ${s.query}" else "pick a key to edit", 1, NamedTextColor.GRAY)
        }
    }

    private fun line(display: DisplayBuilder, text: String, row: Int, color: NamedTextColor) {
        display.moveTo(PREVIEW_X)
        display.styledText(text, row, color)
        display.toStart()
    }

    private fun drawPreview(display: DisplayBuilder, value: String, rows: List<Int>, maxChars: Int) {
        val lines = MiniPreview.linesOf(value)
        lines.take(rows.size).forEachIndexed { i, runs ->
            display.moveTo(PREVIEW_X)
            var budget = maxChars
            for (run in runs) {
                if (budget <= 0) break
                val t = if (run.text.length > budget) run.text.substring(0, budget) else run.text
                budget -= t.length
                if (t.isEmpty()) continue
                display.styledText(
                    t, rows[i], run.color ?: NamedTextColor.WHITE,
                    run.bold, run.italic, run.obfuscated, run.underlined, run.strikethrough,
                )
            }
            if (lines.size > rows.size && i == rows.size - 1) display.styledText("...", rows[i], NamedTextColor.GRAY)
            display.toStart()
        }
    }

    private fun statusOf(session: Session): String {
        val entry = entryOf(session) ?: return ""
        return when {
            entry.values.containsKey(session.language) -> "· edited"
            entry.defaults[session.language] == null -> "· no value"
            else -> "· default"
        }
    }

    // -------------------------------------------------------------- items --

    private fun listItem(session: Session, index: Int): ItemStack? {
        return if (session.showGroups) {
            pageWindow(groups(session), session.page).getOrNull(index)?.let { groupItem(it) }
        } else {
            pageWindow(filteredEntries(session), session.page).getOrNull(index)?.let { keyItem(it, session) }
        }
    }

    private fun buttonItem(s: Session, b: Int): ItemStack? = when (b) {
        0 -> button(Material.ARROW, "<gray>‹ Previous")
        1 -> button(Material.ARROW, "<gray>Next ›")
        2 -> button(if (s.showGroups) Material.CHEST else Material.BOOK, if (s.showGroups) "<gold>Groups" else "<gray>Show groups")
        3 -> button(Material.COMPASS, "<aqua>Search", if (s.query.isBlank()) "<dark_gray>—" else "<gray>${s.query}")
        4 -> if (selectable(s)) button(Material.WRITABLE_BOOK, "<aqua>Edit in book", "<gray>multiline / long") else button(Material.GRAY_DYE, "<dark_gray>Edit")
        5 -> if (selectable(s) && s.pending != null) button(Material.LIME_DYE, "<green>Save") else button(Material.GRAY_DYE, "<dark_gray>Save")
        6 -> if (selectable(s)) button(Material.WATER_BUCKET, "<yellow>Reset") else button(Material.GRAY_DYE, "<dark_gray>Reset")
        7 -> button(Material.PAPER, "<yellow>Language: <white>${s.language}", "<gray>click to cycle")
        8 -> button(Material.BARRIER, "<red>Close")
        else -> null
    }

    private fun groupItem(group: Group): ItemStack =
        button(Material.WRITABLE_BOOK, "<white>${group.owner}", "<gray>${group.count} keys", "<yellow>${group.edited} edited")

    private fun keyItem(entry: TranslationEntry, session: Session): ItemStack {
        val shortKey = entry.key.removePrefix("helix.translations.")
        val effective = entry.values[session.language]
            ?: entry.defaults[session.language]
            ?: entry.defaults[session.view.defaultLanguage]
            ?: ""
        val item = ItemStack.of(if (entry.key == session.selectedKey) Material.MAP else Material.PAPER)
        item.editMeta { meta ->
            meta.itemName(Component.text(ellipsize(shortKey, 40), NamedTextColor.WHITE))
            val lore = mutableListOf(MiniPreview.render(if (effective.isEmpty()) "<dark_gray>(empty)" else effective))
            when {
                entry.values.containsKey(session.language) -> lore += MiniPreview.render("<yellow>edited")
                entry.defaults[session.language] == null -> lore += MiniPreview.render("<gray>no value in ${session.language}")
            }
            meta.lore(lore)
        }
        return item
    }

    private fun button(material: Material, name: String, vararg lore: String): ItemStack {
        val item = ItemStack.of(material)
        item.editMeta { meta ->
            meta.itemName(MiniPreview.render(name))
            if (lore.isNotEmpty()) meta.lore(lore.map { MiniPreview.render(it) })
        }
        return item
    }

    // ------------------------------------------------------------ actions --

    private fun onListClick(player: Player, index: Int) {
        val s = session(player)
        if (s.showGroups) {
            val group = pageWindow(groups(s), s.page).getOrNull(index) ?: return
            s.owner = group.owner
            s.showGroups = false
            s.page = 0
            refresh(player)
        } else {
            val entry = pageWindow(filteredEntries(s), s.page).getOrNull(index) ?: return
            s.selectedKey = entry.key
            s.pending = null
            s.confirmDelete = false
            refresh(player)
        }
    }

    private fun onButtonClick(player: Player, ctx: GuiClickContext, b: Int) {
        val s = session(player)
        when (b) {
            0 -> if (s.page > 0) { s.page--; refresh(player) }
            1 -> if ((s.page + 1) * PAGE_SIZE < listSize(s)) { s.page++; refresh(player) }
            2 -> { s.showGroups = !s.showGroups; s.owner = null; s.query = ""; s.selectedKey = null; s.page = 0; refresh(player) }
            3 -> search(player, ctx)
            4 -> openBook(player)
            5 -> saveCurrent(player)
            6 -> resetCurrent(player)
            7 -> cycleLanguage(player)
            8 -> scope.launch { gui?.close(player) }
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
        player.sendMessage(MiniPreview.render(if (ok) "<green>$success" else "<red>$failure"))
    }

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take((max - 3).coerceAtLeast(0)) + "..."

    private companion object {
        /** Private-use glyph bound to the background; no char literal so file rewrites can't drop it. */
        val GLYPH: String = String(Character.toChars(0xE000))

        /** First list slot (chest row 2); rows 0-1 are the preview panel. */
        const val LIST_START = 18
        const val LIST_COUNT = 27
        const val PAGE_SIZE = 27

        /** First button slot (chest row 5). */
        const val BUTTON_ROW = 45
        const val BUTTON_COUNT = 9

        /** Left pixel of embedded preview text. */
        const val PREVIEW_X = 10

        /** Characters rendered per preview row before truncating. */
        const val PREVIEW_MAX_CHARS = 30

        /** Characters per writable-book page. */
        const val BOOK_PAGE_CHARS = 250
    }
}
