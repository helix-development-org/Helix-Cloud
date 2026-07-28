package de.tytoss.igui.internal

import de.tytoss.igui.gui.GuiAccessDenial
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiCloseContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputRequest
import de.tytoss.igui.gui.GuiInputTimeoutException
import de.tytoss.igui.gui.GuiPage
import de.tytoss.igui.gui.GuiRenderContext
import de.tytoss.igui.gui.GuiSoundConfiguration
import io.papermc.paper.event.packet.UncheckedSignChangeEvent
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.math.BlockPosition
import io.papermc.paper.math.Position
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView
import org.bukkit.plugin.java.JavaPlugin
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * Bukkit-facing runtime for [GuiDefinition]s: tracks which player is
 * viewing which page, renders and (re-)opens inventories, dispatches slot
 * clicks/drags to the right handler, and drives the chat/anvil/sign "input"
 * flow used by [de.tytoss.igui.gui.GuiClickContext]. One instance is created
 * per [de.tytoss.igui.IGui] and registered as a Bukkit [Listener] for its lifetime.
 */
internal class GuiRuntime(
    private val plugin: JavaPlugin,
    private val metrics: MetricsCollector,
    private val sounds: GuiSoundConfiguration,
    private val scope: CoroutineScope,
    private val paperDispatcher: PaperDispatcher,
) : Listener {
    private val sessionsByInventory = IdentityHashMap<Inventory, GuiSession>()
    private val sessionsByPlayer = HashMap<UUID, GuiSession>()
    private val definitions = Collections.newSetFromMap(IdentityHashMap<GuiDefinition, Boolean>())
    private val definitionIds = HashSet<String>()
    private val pendingOpens = HashMap<UUID, Long>()
    private val openSequence = AtomicLong()
    private val cooldowns = HashMap<CooldownKey, Long>()
    private val inputsByPlayer = ConcurrentHashMap<UUID, InputSession>()
    private val inputsByInventory = IdentityHashMap<Inventory, InputSession>()
    private val playerJobs = ConcurrentHashMap<UUID, MutableSet<Job>>()
    private val playerMutexes = ConcurrentHashMap<UUID, Mutex>()
    private val plainText = PlainTextComponentSerializer.plainText()
    private var closed = false

    val definitionCount: Int get() = definitions.size
    val viewerCount: Int get() = sessionsByPlayer.size

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Registers a newly built GUI definition, rejecting a duplicate id.
     *
     * @param definition the definition to register.
     * @throws IllegalArgumentException if a GUI with the same id is already registered.
     * @throws IllegalStateException if the runtime has been [shutdown].
     */
    fun register(definition: GuiDefinition) {
        check(!closed) { "IGui runtime is closed" }
        require(definitionIds.add(definition.id)) { "Duplicate GUI id '${definition.id}'" }
        definitions += definition
    }

    suspend fun open(player: Player, definition: GuiDefinition, pageId: String) = withContext(paperDispatcher) {
        check(!closed) { "IGui runtime is closed" }
        check(definition in definitions) { "GUI '${definition.id}' is closed or belongs to another IGui runtime" }
        val page = definition.compiledPages[pageId]
            ?: throw IllegalArgumentException("GUI '${definition.id}' has no page '$pageId'")
        if (page.permission?.let(player::hasPermission) == false) {
            sounds.error?.play(player)
            page.deniedHandler?.invoke(player, GuiAccessDenial.PERMISSION)
            return@withContext
        }
        val token = openSequence.incrementAndGet()
        pendingOpens[player.uniqueId] = token
        try {
            page.prepareHandler?.invoke(player)
            if (pendingOpens[player.uniqueId] == token) {
                pendingOpens.remove(player.uniqueId)
                openNow(player, definition, page)
            }
        } catch (exception: Exception) {
            if (pendingOpens[player.uniqueId] == token) pendingOpens.remove(player.uniqueId)
            throw exception
        }
    }

    suspend fun refresh(player: Player, definition: GuiDefinition, reloadData: Boolean) =
        withContext(paperDispatcher) {
            val session = sessionsByPlayer[player.uniqueId]?.takeIf { it.definition === definition }
                ?: return@withContext
            if (reloadData) session.page.prepareHandler?.invoke(player)
            refreshNow(player, session)
        }

    suspend fun refresh(definition: GuiDefinition) = withContext(paperDispatcher) {
        sessionsByPlayer.values
            .filter { it.definition === definition }
            .toList()
            .forEach { session ->
                Bukkit.getPlayer(session.playerId)?.let { player ->
                    session.page.prepareHandler?.invoke(player)
                    refreshNow(player, session)
                }
            }
    }

    suspend fun close(player: Player, definition: GuiDefinition) = withContext(paperDispatcher) {
        pendingOpens.remove(player.uniqueId)
        val session = sessionsByPlayer[player.uniqueId]?.takeIf { it.definition === definition }
            ?: return@withContext
        remove(session, invokeCallback = true)
        player.closeInventory()
    }

    suspend fun close(definition: GuiDefinition) = withContext(paperDispatcher) {
        if (!definitions.remove(definition)) return@withContext
        definitionIds.remove(definition.id)
        sessionsByPlayer.values
            .filter { it.definition === definition }
            .toList()
            .forEach { session ->
                remove(session, invokeCallback = true)
                Bukkit.getPlayer(session.playerId)?.closeInventory()
            }
    }

    /**
     * Routes a top-inventory click to a pending input request (anvil result
     * slot) or the clicked page's slot handler, applying permission and
     * cooldown checks and cancelling the event when the page requests it.
     *
     * @param event the raw Bukkit click event.
     */
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val input = inputsByInventory[event.view.topInventory]
        if (input != null) {
            event.isCancelled = true
            if (event.rawSlot == ANVIL_RESULT_SLOT) {
                val player = event.whoClicked as? Player ?: return
                val value = (event.view as? AnvilView)?.renameText.orEmpty()
                completeInput(player, input, value)
            }
            return
        }
        val session = sessionsByInventory[event.view.topInventory] ?: return
        if (session.page.cancelAllInteractions) event.isCancelled = true
        val rawSlot = event.rawSlot
        if (rawSlot !in session.page.handlers.indices) return
        val binding = session.page.handlers[rawSlot] ?: return
        val player = event.whoClicked as? Player ?: return
        if (binding.permission?.let(player::hasPermission) == false) {
            sounds.error?.play(player)
            launchPlayer(player) { session.page.deniedHandler?.invoke(player, GuiAccessDenial.PERMISSION) }
            return
        }
        if (binding.cooldownMillis > 0) {
            val key = CooldownKey(player.uniqueId, session.definition.id, session.page.id, rawSlot)
            val now = System.nanoTime()
            val deadline = cooldowns[key] ?: 0L
            if (now < deadline) {
                sounds.error?.play(player)
                launchPlayer(player) { session.page.deniedHandler?.invoke(player, GuiAccessDenial.COOLDOWN) }
                return
            }
            cooldowns[key] = now + binding.cooldownMillis * 1_000_000L
        }
        metrics.callback()
        sounds.click?.play(player)
        launchPlayer(player) {
            binding.handler(
                GuiClickContext(
                    event,
                    player,
                    session.definition,
                    session.page.id,
                    pageOpener = { target ->
                        sounds.navigation?.play(player)
                        open(player, session.definition, target)
                    },
                    inputRequester = { request -> requestInput(player, session, request) },
                ),
            )
        }
    }

    /**
     * Cancels drags into a GUI's top inventory when the page requests it, or
     * when any dragged slot falls inside the GUI's own inventory (as opposed
     * to the viewer's bottom inventory).
     *
     * @param event the raw Bukkit drag event.
     */
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val session = sessionsByInventory[event.view.topInventory] ?: return
        if (session.page.cancelAllInteractions || event.rawSlots.any { it < session.inventory.size }) {
            event.isCancelled = true
        }
    }

    /**
     * Cleans up session/input state when a player closes an inventory,
     * either completing a pending input flow (chat/anvil/sign) as
     * cancelled, or tearing down the GUI session and invoking its close
     * handler.
     *
     * @param event the raw Bukkit inventory close event.
     */
    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val input = inputsByInventory[event.inventory]
        if (input != null) {
            inputsByInventory.remove(event.inventory)
            scope.launch {
                yield()
                if (inputsByPlayer[event.player.uniqueId] === input) {
                    cancelInput(event.player as Player, input, resume = true)
                }
            }
            return
        }
        sessionsByInventory[event.inventory]?.let { remove(it, invokeCallback = true) }
    }

    /**
     * Tears down all per-player state on disconnect: pending opens,
     * cooldowns, an in-flight input request (cancelled), running handler
     * jobs, and the player's GUI session (with its close handler invoked).
     *
     * @param event the raw Bukkit quit event.
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingOpens.remove(event.player.uniqueId)
        cooldowns.keys.removeIf { it.playerId == event.player.uniqueId }
        inputsByPlayer.remove(event.player.uniqueId)?.let { input ->
            input.inventory?.let(inputsByInventory::remove)
            input.timeoutJob?.cancel()
            input.continuation.cancel(GuiInputCancelledException())
        }
        playerJobs.remove(event.player.uniqueId)?.forEach { it.cancel(CancellationException("Player quit")) }
        playerMutexes.remove(event.player.uniqueId)
        sessionsByPlayer[event.player.uniqueId]?.let { remove(it, invokeCallback = true) }
    }

    /**
     * For a virtual anvil opened by [de.tytoss.igui.gui.GuiClickContext.anvilInput],
     * forces the result slot to always show the renamed item (regardless of
     * vanilla anvil rules) at zero repair cost, so any typed name can be taken.
     *
     * @param event the raw Bukkit prepare-anvil event.
     */
    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        if (event.view.topInventory !in inputsByInventory) return
        val result = ItemStack.of(Material.PAPER)
        result.editMeta { meta -> meta.itemName(Component.text(event.view.renameText.orEmpty())) }
        event.result = result
        event.view.repairCost = 0
    }

    /**
     * Completes a pending [de.tytoss.igui.gui.GuiClickContext.chatInput]
     * request with the player's plain-text message, cancelling the chat
     * event so it is not broadcast.
     *
     * @param event the raw Paper async chat event.
     */
    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val input = inputsByPlayer[event.player.uniqueId]?.takeIf { it.request is GuiInputRequest.Chat } ?: return
        event.isCancelled = true
        val value = plainText.serialize(event.message())
        scope.launch { completeInput(event.player, input, value) }
    }

    /**
     * Completes a pending [de.tytoss.igui.gui.GuiClickContext.signInput]
     * request with the edited sign's plain-text lines, if the edited block
     * matches the virtual sign that was opened.
     *
     * @param event the raw Paper unchecked sign-change event.
     */
    @EventHandler
    fun onSign(event: UncheckedSignChangeEvent) {
        val input = inputsByPlayer[event.player.uniqueId]?.takeIf { it.request is GuiInputRequest.Sign } ?: return
        if (input.signPosition != event.editedBlockPosition) return
        event.isCancelled = true
        val values = event.lines().map(plainText::serialize)
        scope.launch { completeInput(event.player, input, values) }
    }

    /**
     * Cancels every pending input, running handler job and open session
     * (without invoking close handlers), clears all tracked state and
     * unregisters this runtime as a Bukkit listener. Idempotent.
     */
    fun shutdown() {
        if (closed) return
        closed = true
        inputsByPlayer.values.forEach { input ->
            input.timeoutJob?.cancel()
            input.continuation.cancel(GuiInputCancelledException())
        }
        playerJobs.values.flatten().forEach(Job::cancel)
        sessionsByPlayer.values.toList().forEach { session ->
            remove(session, invokeCallback = false)
            Bukkit.getPlayer(session.playerId)?.closeInventory()
        }
        definitions.clear()
        definitionIds.clear()
        pendingOpens.clear()
        cooldowns.clear()
        inputsByPlayer.clear()
        inputsByInventory.clear()
        playerJobs.clear()
        playerMutexes.clear()
        HandlerList.unregisterAll(this)
    }

    private suspend fun openNow(player: Player, definition: GuiDefinition, page: GuiPage) {
        val started = System.nanoTime()
        val previous = sessionsByPlayer.remove(player.uniqueId)
        if (previous != null) {
            sessionsByInventory.remove(previous.inventory)
            if (previous.page !== page) previous.page.cleanupHandlers.forEach { it(previous.playerId) }
            if (previous.definition !== definition) invokeClose(previous)
        }
        val rendered = render(player, definition, page)
        val inventory = Bukkit.createInventory(null, definition.rows * 9, rendered.title)
        inventory.contents = rendered.items
        player.openInventory(inventory)
        sounds.open?.play(player)
        val session = GuiSession(player.uniqueId, definition, page, inventory, rendered.title)
        sessionsByPlayer[player.uniqueId] = session
        sessionsByInventory[inventory] = session
        metrics.opened(System.nanoTime() - started)
        try {
            page.openHandler?.invoke(player)
        } catch (exception: Exception) {
            plugin.logger.severe("Open handler failed in GUI '${definition.id}', page '${page.id}': ${exception.message}")
            exception.printStackTrace()
        }
    }

    private fun refreshNow(player: Player, session: GuiSession) {
        val rendered = render(player, session.definition, session.page)
        if (rendered.title == session.title) {
            session.inventory.contents = rendered.items
            return
        }
        sessionsByInventory.remove(session.inventory)
        val replacement = Bukkit.createInventory(null, session.definition.rows * 9, rendered.title)
        replacement.contents = rendered.items
        session.inventory = replacement
        session.title = rendered.title
        sessionsByInventory[replacement] = session
        player.openInventory(replacement)
    }

    private suspend fun requestInput(player: Player, session: GuiSession, request: GuiInputRequest): Any =
        suspendCancellableCoroutine { continuation ->
            startInput(player, session, request, continuation)
        }

    @Suppress("DEPRECATION")
    private fun startInput(
        player: Player,
        session: GuiSession,
        request: GuiInputRequest,
        continuation: CancellableContinuation<Any>,
    ) {
        if (sessionsByPlayer[player.uniqueId] !== session) {
            continuation.cancel(GuiInputCancelledException())
            return
        }
        remove(session, invokeCallback = false)
        val input = InputSession(request, session.definition, session.page.id, continuation)
        inputsByPlayer.put(player.uniqueId, input)?.let { previous -> cancelInput(player, previous, false) }
        continuation.invokeOnCancellation {
            scope.launch {
                if (inputsByPlayer.remove(player.uniqueId, input)) {
                    input.inventory?.let(inputsByInventory::remove)
                    input.timeoutJob?.cancel()
                }
            }
        }
        when (request) {
            is GuiInputRequest.Chat -> {
                player.closeInventory()
                request.prompt?.let(player::sendMessage)
            }
            is GuiInputRequest.Anvil -> {
                val inventory = Bukkit.createInventory(null, InventoryType.ANVIL, request.title)
                val initial = ItemStack.of(Material.PAPER)
                initial.editMeta { meta -> meta.itemName(Component.text(request.initialValue)) }
                inventory.setItem(0, initial)
                input.inventory = inventory
                inputsByInventory[inventory] = input
                player.openInventory(inventory)
            }
            is GuiInputRequest.Sign -> {
                player.closeInventory()
                val position = Position.block(player.location)
                input.signPosition = position
                val lines = List(4) { index -> request.lines.getOrElse(index) { Component.empty() } }
                player.sendSignChange(player.location, lines)
                player.openVirtualSign(position, Side.FRONT)
            }
        }
        input.timeoutJob = scope.launch {
            delay(INPUT_TIMEOUT)
            if (inputsByPlayer[player.uniqueId] === input) {
                inputsByPlayer.remove(player.uniqueId)
                input.inventory?.let(inputsByInventory::remove)
                input.continuation.cancel(GuiInputTimeoutException())
                sounds.error?.play(player)
                open(player, input.definition, input.pageId)
            }
        }
    }

    private fun completeInput(player: Player, input: InputSession, value: Any) {
        if (!inputsByPlayer.remove(player.uniqueId, input)) return
        input.inventory?.let(inputsByInventory::remove)
        input.timeoutJob?.cancel()
        input.continuation.resume(value)
        sounds.success?.play(player)
        scope.launch {
            yield()
            open(player, input.definition, input.pageId)
        }
    }

    private fun cancelInput(player: Player, input: InputSession, resume: Boolean) {
        inputsByPlayer.remove(player.uniqueId, input)
        input.inventory?.let(inputsByInventory::remove)
        input.timeoutJob?.cancel()
        input.continuation.cancel(GuiInputCancelledException())
        sounds.error?.play(player)
        if (resume) scope.launch { open(player, input.definition, input.pageId) }
    }

    private fun launchPlayer(player: Player, block: suspend () -> Unit) {
        val jobs = playerJobs.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
        val mutex = playerMutexes.computeIfAbsent(player.uniqueId) { Mutex() }
        val job = scope.launch {
            yield()
            mutex.withLock {
                try {
                    block()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    plugin.logger.severe("GUI handler failed for '${player.name}': ${exception.message}")
                    exception.printStackTrace()
                }
            }
        }
        jobs += job
        job.invokeOnCompletion { jobs.remove(job) }
    }

    private fun render(player: Player, definition: GuiDefinition, page: GuiPage): RenderedPage {
        val context = GuiRenderContext(player, definition, page.id)
        val title = page.titleRenderer?.invoke(context) ?: requireNotNull(page.title)
        val items = Array<ItemStack?>(page.itemRenderers.size) { index -> page.itemRenderers[index](context)?.clone() }
        return RenderedPage(title, items)
    }

    private fun remove(session: GuiSession, invokeCallback: Boolean) {
        sessionsByInventory.remove(session.inventory)
        sessionsByPlayer.remove(session.playerId, session)
        session.page.cleanupHandlers.forEach { it(session.playerId) }
        if (invokeCallback) scope.launch { invokeClose(session) }
    }

    private suspend fun invokeClose(session: GuiSession) {
        val player = Bukkit.getPlayer(session.playerId) ?: return
        try {
            session.definition.closeHandler?.invoke(GuiCloseContext(player, session.definition, session.page.id))
        } catch (exception: Exception) {
            plugin.logger.severe("Close handler failed in GUI '${session.definition.id}': ${exception.message}")
            exception.printStackTrace()
        }
    }

    private class GuiSession(
        val playerId: UUID,
        val definition: GuiDefinition,
        val page: GuiPage,
        var inventory: Inventory,
        var title: Component,
    )

    private data class RenderedPage(val title: Component, val items: Array<ItemStack?>)

    private data class CooldownKey(
        val playerId: UUID,
        val guiId: String,
        val pageId: String,
        val slot: Int,
    )

    private class InputSession(
        val request: GuiInputRequest,
        val definition: GuiDefinition,
        val pageId: String,
        val continuation: CancellableContinuation<Any>,
        var inventory: Inventory? = null,
        var signPosition: BlockPosition? = null,
        var timeoutJob: Job? = null,
    )

    private companion object {
        const val ANVIL_RESULT_SLOT = 2
        val INPUT_TIMEOUT = 60.seconds
    }
}
