package org.helix.addons.translations.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputTimeoutException
import de.tytoss.igui.pagination.paginate
import de.tytoss.igui.slot.chestSlot
import de.tytoss.igui.slot.rectTo
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
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** Per-viewer editor state: cached view, active language, list filters and edit buffer. */
private class Session {
    @Volatile var view: TranslationsView = TranslationsView()

    @Volatile var language: String = "en"

    /** Selected owner group in the list, or `null` while browsing groups/search. */
    @Volatile var owner: String? = null

    /** Active search query; blank means browse by [owner]. */
    @Volatile var query: String = ""

    /** Key currently open in the editor. */
    @Volatile var editingKey: String? = null

    /** Unsaved, edited value awaiting the "save" confirmation; `null` means none. */
    @Volatile var pending: String? = null

    /** Whether the next delete click confirms an armed deletion. */
    @Volatile var confirmDelete: Boolean = false
}

/** One owner group in the list GUI. */
private data class Group(val owner: String, val count: Int, val edited: Int)

/**
 * Translations editor Paper component: `/translationsmenu` opens an in-game
 * GUI that browses every `helix.translations.*` message grouped by addon and
 * edits it on a dirt-textured live MiniMessage preview, rendered with IGui
 * font glyphs. Reads and writes travel to the Helix node's admin-gated
 * `helix.translations.*` actions, so edits apply network-wide.
 */
class TranslationsPlugin : JavaPlugin() {
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private var igui: IGui? = null
    private var listGui: GuiDefinition? = null
    private var editorGui: GuiDefinition? = null
    private var client: NodeClient? = null
    private lateinit var scope: CoroutineScope

    /** Dispatcher running coroutines on the Bukkit main thread. */
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) {
                server.scheduler.runTask(this@TranslationsPlugin, block)
            }
        }
    }

    /** Boots the node client, installs the dirt texture and builds both GUIs. */
    override fun onEnable() {
        val nodeClient = NodeClient.fromEnvironment()
        if (nodeClient == null) {
            logger.warning("No Helix environment found — translations editor disabled.")
            server.pluginManager.disablePlugin(this)
            return
        }
        client = nodeClient
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope.launch {
            val installed = awaitSharedIGui()
            installed.saveTexture(
                GuiTextureDefinition("translations.dirt", "", Key.key("translations", "gui"), 176, 222),
            )
            igui = installed
            listGui = buildListGui(installed)
            editorGui = buildEditorGui(installed)
            logger.info("Translations editor GUI ready")
        }
    }

    /** Cancels this plugin's coroutine scope; the shared IGui is left running. */
    override fun onDisable() {
        if (::scope.isInitialized) {
            igui = null
            scope.cancel()
        }
        client?.close()
        client = null
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
        if (listGui == null) {
            player.sendMessage(MiniPreview.render("<gray>Translations editor is still starting up…"))
            return true
        }
        scope.launch {
            val session = session(player)
            session.owner = null
            session.query = ""
            loadView(player, session)
            listGui?.open(player)
        }
        return true
    }

    private fun session(player: Player): Session = sessions.getOrPut(player.uniqueId) { Session() }

    private suspend fun loadView(player: Player, session: Session) {
        val view = withContext(Dispatchers.IO) { client?.view(player.name) } ?: return
        session.view = view
        if (session.language !in view.languages) {
            session.language = view.defaultLanguage
        }
    }

    private fun currentValue(session: Session): String {
        val entry = session.view.entries.find { it.key == session.editingKey } ?: return ""
        return entry.values[session.language]
            ?: entry.defaults[session.language]
            ?: entry.defaults[session.view.defaultLanguage]
            ?: ""
    }

    private fun editingEntry(session: Session): TranslationEntry? =
        session.view.entries.find { it.key == session.editingKey }

    // ---------------------------------------------------------------- list --

    private suspend fun buildListGui(installed: IGui): GuiDefinition = installed.gui("translations.list") {
        rows = 6
        landingPage = "groups"

        page("groups") {
            permission = "helix.admin"
            prepare { player -> loadView(player, session(player)) }
            title { _ -> centeredText("Translations", 0, 88, NamedTextColor.DARK_GRAY) }
            paginate<Group>(chestSlot(1, 1) rectTo chestSlot(4, 9)) {
                previousSlot = chestSlot(6, 1)
                nextSlot = chestSlot(6, 9)
                source { player ->
                    val s = session(player)
                    s.view.entries.groupBy { it.owner }
                        .map { (owner, list) -> Group(owner, list.size, list.count { it.values.containsKey(s.language) }) }
                        .sortedBy { it.owner }
                }
                render { _, group -> groupItem(group) }
                onClick { ctx, group ->
                    val s = session(ctx.player)
                    s.owner = group.owner
                    s.query = ""
                    ctx.openPage("keys")
                }
            }
            onClick(chestSlot(5, 3)) { ctx -> startSearch(ctx.player, ctx) }
            onClick(chestSlot(5, 5)) { ctx -> ctx.openPage("languages") }
            onClick(chestSlot(5, 7)) { ctx -> ctx.close() }
            item(chestSlot(5, 3)) { button(Material.COMPASS, "<aqua>Search") }
            item(chestSlot(5, 5)) { button(Material.BOOK, "<yellow>Languages") }
            item(chestSlot(5, 7)) { button(Material.BARRIER, "<red>Close") }
        }

        page("keys") {
            permission = "helix.admin"
            prepare { player -> loadView(player, session(player)) }
            title { context ->
                val s = session(context.player)
                val label = if (s.query.isNotBlank()) "Search: ${s.query}" else (s.owner ?: "Keys")
                centeredText(ellipsize(label, 30), 0, 88, NamedTextColor.DARK_GRAY)
            }
            paginate<TranslationEntry>(chestSlot(1, 1) rectTo chestSlot(4, 9)) {
                previousSlot = chestSlot(6, 1)
                nextSlot = chestSlot(6, 9)
                source { player -> keyEntries(session(player)) }
                render { context, entry -> keyItem(entry, session(context.player).language, session(context.player)) }
                onClick { ctx, entry ->
                    val s = session(ctx.player)
                    s.editingKey = entry.key
                    s.pending = null
                    s.confirmDelete = false
                    editorGui?.open(ctx.player)
                }
            }
            onClick(chestSlot(5, 3)) { ctx -> ctx.openPage("groups") }
            onClick(chestSlot(5, 5)) { ctx -> startSearch(ctx.player, ctx) }
            item(chestSlot(5, 3)) { button(Material.ARROW, "<gray>Back to groups") }
            item(chestSlot(5, 5)) { button(Material.COMPASS, "<aqua>Search") }
        }

        page("languages") {
            permission = "helix.admin"
            prepare { player -> loadView(player, session(player)) }
            title { context ->
                val s = session(context.player)
                centeredText("Languages — active: ${s.language}", 0, 88, NamedTextColor.DARK_GRAY)
            }
            paginate<String>(chestSlot(1, 1) rectTo chestSlot(4, 9)) {
                previousSlot = chestSlot(6, 1)
                nextSlot = chestSlot(6, 9)
                source { player -> session(player).view.languages }
                render { context, language -> languageItem(language, session(context.player)) }
                onClick { ctx, language ->
                    session(ctx.player).language = language
                    ctx.gui.refresh(ctx.player)
                }
            }
            onClick(chestSlot(5, 1)) { ctx -> ctx.openPage("groups") }
            onClick(chestSlot(5, 3)) { ctx -> addLanguage(ctx.player, ctx) }
            onClick(chestSlot(5, 5)) { ctx -> languageAction(ctx.player, ctx) { client, name, lang -> client.setDefaultLanguage(name, lang) } }
            onClick(chestSlot(5, 7)) { ctx -> languageAction(ctx.player, ctx) { client, name, lang -> client.removeLanguage(name, lang) } }
            item(chestSlot(5, 1)) { button(Material.ARROW, "<gray>Back to groups") }
            item(chestSlot(5, 3)) { button(Material.SLIME_BALL, "<green>Add language") }
            item(chestSlot(5, 5)) { context -> button(Material.NETHER_STAR, "<gold>Set default", "<gray>active: ${session(context.player).language}") }
            item(chestSlot(5, 7)) { context -> button(Material.BARRIER, "<red>Remove language", "<gray>active: ${session(context.player).language}") }
        }
    }

    private fun keyEntries(session: Session): List<TranslationEntry> {
        val all = session.view.entries
        val query = session.query.trim().lowercase()
        val lang = session.language
        val filtered = if (query.isNotBlank()) {
            all.filter { entry ->
                entry.key.lowercase().contains(query) ||
                    (entry.values[lang] ?: entry.defaults[lang] ?: "").lowercase().contains(query)
            }
        } else {
            all.filter { it.owner == session.owner }
        }
        return filtered.sortedBy { it.key }
    }

    private suspend fun startSearch(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val query = try {
            ctx.anvilInput(title = Component.text("Search translations"))
        } catch (ignored: GuiInputCancelledException) {
            return
        } catch (ignored: GuiInputTimeoutException) {
            return
        }
        val s = session(player)
        s.query = query.trim()
        s.owner = null
        ctx.openPage("keys")
    }

    private suspend fun addLanguage(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val language = try {
            ctx.anvilInput(title = Component.text("New language code (e.g. fr)"))
        } catch (ignored: GuiInputCancelledException) {
            return
        } catch (ignored: GuiInputTimeoutException) {
            return
        }.trim().lowercase()
        if (language.isBlank()) return
        val nodeClient = client ?: return
        val ok = withContext(Dispatchers.IO) { nodeClient.addLanguage(player.name, language) }
        feedback(player, ok, "Added $language", "Could not add $language")
        loadView(player, session(player))
        ctx.openPage("languages")
    }

    private suspend fun languageAction(
        player: Player,
        ctx: de.tytoss.igui.gui.GuiClickContext,
        action: (NodeClient, String, String) -> Boolean,
    ) {
        val s = session(player)
        val nodeClient = client ?: return
        val language = s.language
        val ok = withContext(Dispatchers.IO) { action(nodeClient, player.name, language) }
        feedback(player, ok, "Done: $language", "Failed for $language")
        loadView(player, s)
        ctx.gui.refresh(player)
    }

    // -------------------------------------------------------------- editor --

    private suspend fun buildEditorGui(installed: IGui): GuiDefinition = installed.gui("translations.editor") {
        rows = 6
        landingPage = "editor"
        page("editor") {
            permission = "helix.admin"
            prepare { player -> loadView(player, session(player)) }
            title { context ->
                moveTo(0)
                texture(installed.cachedTexture("translations.dirt"))
                toStart()
                val s = session(context.player)
                val key = s.editingKey ?: return@title
                val shortKey = key.removePrefix("helix.translations.")
                centeredText(ellipsize(shortKey, 28), 0, 88, NamedTextColor.WHITE)
                moveTo(8)
                val marker = if (s.pending != null) " (unsaved)" else ""
                styledText("Language: ${s.language}$marker", 1, NamedTextColor.YELLOW)
                toStart()
                val value = s.pending ?: currentValue(s)
                if (value.isEmpty()) {
                    moveTo(8)
                    styledText("(empty)", 2, NamedTextColor.GRAY, italic = true)
                    toStart()
                } else {
                    MiniPreview.draw(this, value, 8, 2, 5, PREVIEW_MAX_CHARS)
                }
            }
            item(chestSlot(6, 1)) { button(Material.ARROW, "<gray>Back") }
            item(chestSlot(6, 2)) { button(Material.PAPER, "<yellow>Language", "<gray>cycle active language") }
            item(chestSlot(6, 4)) { button(Material.WRITABLE_BOOK, "<aqua>Edit value") }
            item(chestSlot(6, 5)) { context -> if (session(context.player).pending != null) button(Material.LIME_DYE, "<green>Save") else null }
            item(chestSlot(6, 6)) { context -> if (session(context.player).pending != null) button(Material.GRAY_DYE, "<gray>Discard") else null }
            item(chestSlot(6, 8)) { button(Material.WATER_BUCKET, "<yellow>Reset to default") }
            item(chestSlot(6, 9)) { context -> deleteButton(session(context.player)) }
            onClick(chestSlot(6, 1)) { ctx ->
                session(ctx.player).confirmDelete = false
                listGui?.open(ctx.player)
            }
            onClick(chestSlot(6, 2)) { ctx -> cycleLanguage(ctx.player, ctx) }
            onClick(chestSlot(6, 4)) { ctx -> editValue(ctx.player, ctx) }
            onClick(chestSlot(6, 5)) { ctx -> saveValue(ctx.player, ctx) }
            onClick(chestSlot(6, 6)) { ctx ->
                val s = session(ctx.player)
                s.pending = null
                s.confirmDelete = false
                ctx.gui.refresh(ctx.player)
            }
            onClick(chestSlot(6, 8)) { ctx -> resetValue(ctx.player, ctx) }
            onClick(chestSlot(6, 9)) { ctx -> deleteKey(ctx.player, ctx) }
        }
    }

    private fun deleteButton(session: Session): ItemStack? {
        val entry = editingEntry(session) ?: return null
        if (entry.defaults.isNotEmpty()) return null // only custom-created keys are deletable
        return if (session.confirmDelete) {
            button(Material.REDSTONE_BLOCK, "<red>Confirm delete", "<gray>click again to delete this key")
        } else {
            button(Material.BARRIER, "<red>Delete key")
        }
    }

    private suspend fun cycleLanguage(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val s = session(player)
        val languages = s.view.languages
        if (languages.isNotEmpty()) {
            val index = languages.indexOf(s.language).coerceAtLeast(0)
            s.language = languages[(index + 1) % languages.size]
        }
        s.pending = null
        s.confirmDelete = false
        ctx.gui.refresh(player)
    }

    private suspend fun editValue(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val s = session(player)
        s.confirmDelete = false
        val current = s.pending ?: currentValue(s)
        val entered = try {
            if (current.length > ANVIL_MAX_CHARS) {
                ctx.chatInput(MiniPreview.render("<gray>Type the new value in chat (it is long); <white>cancel</white> to abort:"))
            } else {
                ctx.anvilInput(
                    initialValue = current,
                    title = Component.text("Edit — see live preview"),
                    preview = { typed -> MiniPreview.render(typed.ifEmpty { " " }) },
                )
            }
        } catch (ignored: GuiInputCancelledException) {
            return
        } catch (ignored: GuiInputTimeoutException) {
            return
        }
        if (entered.equals("cancel", ignoreCase = true)) {
            editorGui?.open(player)
            return
        }
        s.pending = entered
        editorGui?.open(player)
    }

    private suspend fun saveValue(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val s = session(player)
        val key = s.editingKey ?: return
        val pending = s.pending ?: return
        val nodeClient = client ?: return
        val ok = withContext(Dispatchers.IO) { nodeClient.set(player.name, key, s.language, pending) }
        feedback(player, ok, "Saved ${key.removePrefix("helix.translations.")} (${s.language})", "Save failed")
        if (ok) s.pending = null
        loadView(player, s)
        ctx.gui.refresh(player)
    }

    private suspend fun resetValue(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val s = session(player)
        val key = s.editingKey ?: return
        val nodeClient = client ?: return
        s.confirmDelete = false
        val ok = withContext(Dispatchers.IO) { nodeClient.reset(player.name, key, s.language) }
        feedback(player, ok, "Reset ${key.removePrefix("helix.translations.")} (${s.language})", "Nothing to reset")
        s.pending = null
        loadView(player, s)
        ctx.gui.refresh(player)
    }

    private suspend fun deleteKey(player: Player, ctx: de.tytoss.igui.gui.GuiClickContext) {
        val s = session(player)
        val key = s.editingKey ?: return
        val entry = editingEntry(s) ?: return
        if (entry.defaults.isNotEmpty()) return
        if (!s.confirmDelete) {
            s.confirmDelete = true
            ctx.gui.refresh(player)
            return
        }
        val nodeClient = client ?: return
        val ok = withContext(Dispatchers.IO) { nodeClient.deleteKey(player.name, key) }
        feedback(player, ok, "Deleted ${key.removePrefix("helix.translations.")}", "Delete failed")
        s.confirmDelete = false
        s.editingKey = null
        loadView(player, s)
        listGui?.open(player)
    }

    // --------------------------------------------------------------- items --

    private fun groupItem(group: Group): ItemStack =
        button(
            Material.WRITABLE_BOOK,
            "<white>${group.owner}",
            "<gray>${group.count} keys",
            "<yellow>${group.edited} edited",
        )

    private fun keyItem(entry: TranslationEntry, language: String, session: Session): ItemStack {
        val shortKey = entry.key.removePrefix("helix.translations.")
        val effective = entry.values[language]
            ?: entry.defaults[language]
            ?: entry.defaults[session.view.defaultLanguage]
            ?: ""
        val item = ItemStack(Material.PAPER)
        item.editMeta { meta ->
            meta.displayName(Component.text(ellipsize(shortKey, 40), NamedTextColor.WHITE))
            val lore = mutableListOf(MiniPreview.render(if (effective.isEmpty()) "<dark_gray>(empty)" else effective))
            when {
                entry.values.containsKey(language) -> lore += MiniPreview.render("<yellow>edited")
                entry.defaults[language] == null -> lore += MiniPreview.render("<gray>no value in $language")
            }
            meta.lore(lore)
        }
        return item
    }

    private fun languageItem(language: String, session: Session): ItemStack {
        val markers = buildList {
            if (language == session.view.defaultLanguage) add("<gold>★ default")
            if (language == session.language) add("<green>active")
        }
        return button(Material.PAPER, "<white>$language", *markers.toTypedArray())
    }

    private fun button(material: Material, name: String, vararg lore: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(MiniPreview.render(name))
            if (lore.isNotEmpty()) meta.lore(lore.map { MiniPreview.render(it) })
        }
        return item
    }

    private fun feedback(player: Player, ok: Boolean, success: String, failure: String) {
        val message = if (ok) "<green>$success" else "<red>$failure"
        player.sendMessage(MiniPreview.render(message))
    }

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take((max - 3).coerceAtLeast(0)) + "..."

    private companion object {
        /** Above this length the anvil's ~50-char client cap forces chat input. */
        const val ANVIL_MAX_CHARS = 48

        /** Characters rendered per preview row before truncating with `...`. */
        const val PREVIEW_MAX_CHARS = 30
    }
}
