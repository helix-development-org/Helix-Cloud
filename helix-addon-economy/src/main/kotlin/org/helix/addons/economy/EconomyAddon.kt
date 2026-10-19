package org.helix.addons.economy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerDataProvider
import org.helix.api.message.Messages
import org.helix.api.release.ReleaseReport
import org.helix.api.release.ReleaseReset
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Economy addon: the network currency „Kronen“ (crowns).
 *
 * Whole-crown balances per player uuid in PostgreSQL (schema `economy`,
 * with a booking ledger and daily limit counters) or, in json mode, in the
 * addon's document storage. Player commands `/balance`, `/kronen
 * [verlauf]` and `/pay`, admin actions `eco.*` and the bridge-invocable
 * machine API `eco.api.*` used by game modes and the Vault bridge.
 *
 * Machine API (every player argument is a name or a uuid; results are in
 * `lines[0]`):
 *
 * - `eco.api.balance <player>` → `"<balance>"`.
 * - `eco.api.deposit <player> <amount> [reason] [ref|-] [limitKey limitMax]` → `"<newBalance>"`.
 * - `eco.api.withdraw <player> <amount> [reason] [ref|-] [limitKey limitMax]` → `"<newBalance>"`.
 * - `eco.api.transfer <from> <to> <amount> [reason] [feePermille] [ref]` → JSON
 *   `{"from","to","amount","fee","received","fromBalance","toBalance"}` (uuids, crowns).
 * - `eco.api.history <player> [limit=20]` → JSON array of
 *   `{"id","uuid","delta","balanceAfter","reason","ref","counterparty","fee","at"}` (newest first, `at` in epoch seconds).
 * - `eco.api.limit <player> <limitKey> [limitMax]` → JSON `{"key","used","remaining"}` (`remaining` only with `limitMax`).
 *
 * Errors are failed results whose `lines[0]` is one of `insufficient funds`,
 * `limit reached: <remaining>`, `unknown player: <arg>`,
 * `invalid amount: <arg>` or a usage line. Daily limits count the absolute
 * booked amount per player and calendar day (Europe/Berlin).
 */
class EconomyAddon : AddonBase() {
    private val logger = LoggerFactory.getLogger(EconomyAddon::class.java)
    private val json = Json { encodeDefaults = true }
    private lateinit var repository: BalanceRepository
    private lateinit var migration: LegacyMigration
    private lateinit var msg: Messages

    /**
     * Opens the repository, migrates legacy balances and registers commands and actions.
     */
    override fun enable() {
        repository = openRepository()
        migration = LegacyMigration(context.storage(), repository, context::resolvePlayerUuid)
        runCatching { migration.run() }
            .onSuccess { if (it > 0) logger.info("Migrated {} legacy balances to crown accounts", it) }
            .onFailure { logger.warn("Legacy balance migration failed: {}", it.message) }
        msg = loadMessages()
        registerPlayerCommands()
        registerAdminActions()
        registerMachineApi()
        panel(
            "economy",
            "Kronen",
            "/panel.html",
            "<path d=\"M4 17h16l1-10-5 4-4-6-4 6-5-4 1 10z\"/><path d=\"M4 20h16\"/>",
        )
        context.registerPlayerListener(
            /** Remembers the name, claims parked legacy balances and publishes the balance on join. */
            object : org.helix.api.addon.PlayerListener {
                override fun onJoin(player: org.helix.api.player.OnlinePlayer) {
                    val holder = player.uuid?.let { Holder(it.lowercase(), player.name) } ?: resolve(player.name) ?: return
                    repository.rename(holder)
                    runCatching { migration.claim(holder) }
                    publish(holder)
                }
            },
        )
        context.registerPlayerDataProvider(
            /** Exports a player's balance and bookings; deletes the account on request. */
            object : PlayerDataProvider {
                override fun export(player: String): String? {
                    val holder = resolve(player) ?: return null
                    val account = repository.account(holder.uuid) ?: return null
                    return json.encodeToString(
                        buildJsonObject {
                            put("balance", account.balance)
                            put("history", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LedgerEntry.serializer()), repository.history(holder.uuid, HISTORY_EXPORT)))
                        },
                    )
                }

                override fun delete(player: String): Boolean {
                    val holder = resolve(player) ?: return false
                    val existed = repository.delete(holder.uuid)
                    if (existed) {
                        publish(holder)
                    }
                    return existed
                }
            },
        )
    }

    /** Closes the repository (connection pool). */
    override fun onDisable() {
        if (::repository.isInitialized) {
            repository.close()
        }
    }

    private fun openRepository(): BalanceRepository {
        val connection = context.storageConnection()
        if (connection != null && connection.mode.equals("postgres", ignoreCase = true)) {
            return runCatching { PostgresBalanceRepository(connection, STARTING_BALANCE) }
                .onFailure { logger.warn("PostgreSQL economy unavailable, using document store: {}", it.message) }
                .getOrElse { DocumentBalanceRepository(context.storage(), STARTING_BALANCE) }
        }
        return DocumentBalanceRepository(context.storage(), STARTING_BALANCE)
    }

    private fun registerPlayerCommands() {
        context.registerAction(
            ActionDescriptor(name = "balance", description = "Shows your crown balance.", usage = "balance", playerCommand = true),
        ) { invocation -> balanceCommand(invocation) }
        context.registerAction(
            ActionDescriptor(
                name = "kronen",
                description = "Shows your crowns; 'verlauf' lists your last bookings.",
                usage = "kronen [verlauf]",
                playerCommand = true,
            ),
        ) { invocation ->
            val sub = invocation.arguments.getOrNull(1)?.lowercase()
            if (sub == "verlauf" || sub == "history") historyCommand(invocation) else balanceCommand(invocation)
        }
        context.registerAction(
            ActionDescriptor(
                name = "pay",
                description = "Transfers crowns to another player.",
                usage = "pay <player> <amount>",
                playerCommand = true,
            ),
        ) { invocation -> pay(invocation) }
    }

    /**
     * Counts (plan) or deletes (run) all crown accounts for the network release; players start again with the
     * starting balance on their next booking.
     *
     * @param arguments `plan` or `run`.
     * @return a [ReleaseReport] line.
     */
    internal fun releaseReset(arguments: List<String>): ActionResult {
        val dryRun = ReleaseReset.dryRun(arguments)
            ?: return ActionResult.error("usage: eco.release.reset <plan|run>")
        val known = if (dryRun) emptyList() else repository.accounts()
        val deleted = LinkedHashMap(repository.wipeAll(dryRun))
        // Paper reads balances from bridge values first; republish the starting balance so no stale value survives.
        known.forEach { account -> publish(Holder(account.uuid, account.name)) }
        deleted["legacy_unresolved"] = migration.clearUnresolved(dryRun)
        return ActionResult.ok(
            ReleaseReport(
                addon = "economy",
                mode = if (dryRun) ReleaseReset.PLAN else ReleaseReset.RUN,
                deleted = deleted,
                kept = mapOf("legacy_migrated" to "reference copy of the pre-crown document"),
            ).toJson(),
        )
    }

    private fun registerAdminActions() {
        action(
            "eco" + ReleaseReset.ACTION_SUFFIX,
            "Release reset: deletes every crown account with ledger, daily counters and parked legacy balances.",
            "eco.release.reset <plan|run>",
            permission = ReleaseReset.PERMISSION,
        ) { invocation -> releaseReset(invocation.arguments) }
        action("eco.get", "Shows a player's crown balance.", "eco.get <player>") { invocation ->
            val arg = invocation.arguments.firstOrNull() ?: return@action ActionResult.error("usage: eco.get <player>")
            val holder = resolve(arg) ?: return@action ActionResult.error("unknown player: $arg")
            ActionResult.ok("${display(holder)} has ${repository.balance(holder.uuid)} crowns")
        }
        action("eco.give", "Adds crowns to a player.", "eco.give <player> <amount>") { invocation ->
            adminChange(invocation) { holder, amount -> repository.change(holder, amount, "admin") }
        }
        action("eco.take", "Removes crowns from a player.", "eco.take <player> <amount>") { invocation ->
            adminChange(invocation) { holder, amount -> repository.change(holder, -amount, "admin") }
        }
        action("eco.set", "Sets a player's crown balance.", "eco.set <player> <amount>") { invocation ->
            adminChange(invocation) { holder, amount -> Booking.Done(repository.set(holder, amount, "admin")) }
        }
        action("eco.export", "Exports all balances as JSON name → crowns (used by the dashboard).", "eco.export") {
            ActionResult.ok(json.encodeToString(repository.accounts().associate { (it.name.ifBlank { it.uuid }) to it.balance }))
        }
    }

    private fun registerMachineApi() {
        action(
            "eco.api.balance",
            "Machine API: a player's crown balance as a plain number.",
            "eco.api.balance <player>",
            bridgeInvocable = true,
        ) { invocation ->
            val arg = invocation.arguments.firstOrNull() ?: return@action ActionResult.error("usage: eco.api.balance <player>")
            val holder = resolve(arg) ?: return@action ActionResult.error("unknown player: $arg")
            ActionResult.ok(repository.balance(holder.uuid).toString())
        }
        action(
            "eco.api.deposit",
            "Machine API: adds crowns and answers with the new balance.",
            "eco.api.deposit <player> <amount> [reason] [ref|-] [limitKey limitMax]",
            bridgeInvocable = true,
        ) { invocation -> apiChange(invocation, sign = 1) }
        action(
            "eco.api.withdraw",
            "Machine API: removes crowns when covered and answers with the new balance.",
            "eco.api.withdraw <player> <amount> [reason] [ref|-] [limitKey limitMax]",
            bridgeInvocable = true,
        ) { invocation -> apiChange(invocation, sign = -1) }
        action(
            "eco.api.transfer",
            "Machine API: atomic transfer with optional fee; answers with a JSON receipt.",
            "eco.api.transfer <from> <to> <amount> [reason] [feePermille] [ref]",
            bridgeInvocable = true,
        ) { invocation -> apiTransfer(invocation) }
        action(
            "eco.api.history",
            "Machine API: a player's latest bookings as JSON.",
            "eco.api.history <player> [limit]",
            bridgeInvocable = true,
        ) { invocation ->
            val arg = invocation.arguments.firstOrNull() ?: return@action ActionResult.error("usage: eco.api.history <player> [limit]")
            val holder = resolve(arg) ?: return@action ActionResult.error("unknown player: $arg")
            val limit = invocation.arguments.getOrNull(1)?.toIntOrNull()?.coerceIn(1, HISTORY_MAX) ?: HISTORY_DEFAULT
            ActionResult.ok(json.encodeToString(repository.history(holder.uuid, limit)))
        }
        action(
            "eco.api.limit",
            "Machine API: today's value of a daily limit counter as JSON.",
            "eco.api.limit <player> <limitKey> [limitMax]",
            bridgeInvocable = true,
        ) { invocation ->
            val arg = invocation.arguments.getOrNull(0)
            val key = invocation.arguments.getOrNull(1)
            if (arg == null || key == null) {
                return@action ActionResult.error("usage: eco.api.limit <player> <limitKey> [limitMax]")
            }
            val holder = resolve(arg) ?: return@action ActionResult.error("unknown player: $arg")
            val used = repository.dailyUsed(holder.uuid, key)
            val max = invocation.arguments.getOrNull(2)?.toLongOrNull()
            ActionResult.ok(
                json.encodeToString(
                    buildJsonObject {
                        put("key", key)
                        put("used", used)
                        if (max != null) put("remaining", (max - used).coerceAtLeast(0))
                    },
                ),
            )
        }
    }

    /**
     * Resolves a player argument: a uuid, an online player's name or a name
     * from the node's identity registry. `null` for names that never joined.
     */
    private fun resolve(argument: String): Holder? {
        val trimmed = argument.trim()
        if (UUID_PATTERN.matches(trimmed)) {
            val uuid = trimmed.lowercase()
            val name = context.onlinePlayers().firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.name
                ?: repository.account(uuid)?.name?.takeIf { it.isNotBlank() }
                ?: context.lastKnownName(uuid)
                ?: ""
            return Holder(uuid, name)
        }
        context.onlinePlayers().firstOrNull { it.name.equals(trimmed, ignoreCase = true) && it.uuid != null }?.let {
            return Holder(it.uuid!!.lowercase(), it.name)
        }
        val uuid = context.resolvePlayerUuid(trimmed) ?: context.resolvePlayerUuid(trimmed.lowercase()) ?: return null
        return Holder(uuid.lowercase(), trimmed)
    }

    /** Name shown in admin answers. */
    private fun display(holder: Holder): String = holder.name.ifBlank { holder.uuid }

    /** Publishes the balance as the `economy.balance.<lowercase name>` bridge value. */
    private fun publish(holder: Holder) {
        val name = holder.name.ifBlank { repository.account(holder.uuid)?.name.orEmpty() }
        if (name.isNotBlank()) {
            context.publishBridgeValue("economy.balance.${name.lowercase()}", repository.balance(holder.uuid).toString())
        }
    }

    private fun apiChange(invocation: ActionInvocation, sign: Int): ActionResult {
        val args = invocation.arguments
        val arg = args.getOrNull(0) ?: return ActionResult.error("usage: ${invocation.action} <player> <amount>")
        val amount = args.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
            ?: return ActionResult.error("invalid amount: ${args.getOrNull(1)}")
        val holder = resolve(arg) ?: return ActionResult.error("unknown player: $arg")
        val reason = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "api"
        val ref = args.getOrNull(3)?.takeIf { it.isNotBlank() && it != "-" }
        val limitKey = args.getOrNull(4)
        val limit = if (limitKey != null) {
            val max = args.getOrNull(5)?.toLongOrNull()?.takeIf { it >= 0 }
                ?: return ActionResult.error("invalid limit: ${args.getOrNull(5)}")
            DailyLimit(limitKey, max)
        } else {
            null
        }
        return when (val booking = repository.change(holder, sign * amount, reason, ref, limit)) {
            is Booking.Done -> {
                publish(holder)
                ActionResult.ok(booking.balance.toString())
            }
            Booking.InsufficientFunds -> ActionResult.error("insufficient funds")
            is Booking.LimitReached -> ActionResult.error("limit reached: ${booking.remaining}")
        }
    }

    private fun apiTransfer(invocation: ActionInvocation): ActionResult {
        val args = invocation.arguments
        if (args.size < 3) {
            return ActionResult.error("usage: eco.api.transfer <from> <to> <amount> [reason] [feePermille] [ref]")
        }
        val from = resolve(args[0]) ?: return ActionResult.error("unknown player: ${args[0]}")
        val to = resolve(args[1]) ?: return ActionResult.error("unknown player: ${args[1]}")
        val amount = args[2].toLongOrNull()?.takeIf { it > 0 } ?: return ActionResult.error("invalid amount: ${args[2]}")
        if (from.uuid == to.uuid) {
            return ActionResult.error("cannot transfer to the same player")
        }
        val reason = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "transfer"
        val fee = args.getOrNull(4)?.let { it.toIntOrNull()?.takeIf { value -> value in 0..1000 } ?: return ActionResult.error("invalid fee: $it") } ?: 0
        val ref = args.getOrNull(5)?.takeIf { it.isNotBlank() && it != "-" }
        return when (val outcome = repository.transfer(from, to, amount, fee, reason, ref)) {
            is TransferOutcome.Done -> {
                publish(from)
                publish(to)
                ActionResult.ok(json.encodeToString(outcome.receipt))
            }
            TransferOutcome.InsufficientFunds -> ActionResult.error("insufficient funds")
        }
    }

    private fun balanceCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull() ?: return ActionResult.error("missing executing player")
        val balance = resolve(executor)?.let { repository.balance(it.uuid) } ?: STARTING_BALANCE
        return ActionResult.ok(msg.formatFor(executor, "balance", "balance" to format(balance)))
    }

    private fun historyCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull() ?: return ActionResult.error("missing executing player")
        val holder = resolve(executor) ?: return ActionResult.ok(msg.formatFor(executor, "history.empty"))
        val entries = repository.history(holder.uuid, HISTORY_DEFAULT)
        if (entries.isEmpty()) {
            return ActionResult.ok(msg.formatFor(executor, "history.empty"))
        }
        val lines = mutableListOf(msg.formatFor(executor, "history.header", "count" to entries.size.toString()))
        entries.forEach { entry ->
            val key = if (entry.delta >= 0) "history.in" else "history.out"
            lines += msg.screenFor(
                executor,
                key,
                "amount" to format(kotlin.math.abs(entry.delta)),
                "balance" to format(entry.balanceAfter),
                "reason" to reasonLabel(executor, entry.reason),
                "date" to DATE.format(Instant.ofEpochSecond(entry.at)),
            )
        }
        return ActionResult(true, lines)
    }

    /** Translated booking reason (`reason.<reason>`), the raw reason when no translation exists. */
    private fun reasonLabel(player: String, reason: String): String {
        val key = "reason.$reason"
        val raw = msg.rawFor(player, key)
        return if (raw == key || raw.isBlank()) reason else raw
    }

    /**
     * Transfers crowns to a target that must be a real, previously-seen
     * player — online now or resolvable through the node's identity
     * registry — so a misspelled or never-seen name errors out instead of
     * silently creating an account nobody will ever claim.
     */
    private fun pay(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.getOrNull(0) ?: return ActionResult.error("missing executing player")
        val targetName = invocation.arguments.getOrNull(1) ?: return ActionResult.error(msg.formatFor(executor, "error.usage"))
        val amount = invocation.arguments.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
            ?: return ActionResult.error(msg.formatFor(executor, "error.usage"))
        if (executor.equals(targetName, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(executor, "error.self"))
        }
        val target = resolve(targetName) ?: return ActionResult.error(msg.formatFor(executor, "error.unknown"))
        val sender = resolve(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.funds"))
        if (sender.uuid == target.uuid) {
            return ActionResult.error(msg.formatFor(executor, "error.self"))
        }
        return when (repository.transfer(sender, target, amount, 0, "pay")) {
            is TransferOutcome.Done -> {
                publish(sender)
                publish(target)
                context.actions.invoke(
                    ActionInvocation(
                        "player.message",
                        listOf(
                            target.name,
                            msg.formatFor(target.name, "pay.received", "sender" to executor, "amount" to format(amount)),
                        ),
                        ActionSource.ADDON,
                    ),
                )
                ActionResult.ok(msg.formatFor(executor, "pay.sent", "amount" to format(amount), "target" to target.name))
            }
            TransferOutcome.InsufficientFunds -> ActionResult.error(msg.formatFor(executor, "error.funds"))
        }
    }

    private fun adminChange(invocation: ActionInvocation, change: (Holder, Long) -> Booking): ActionResult {
        val arg = invocation.arguments.getOrNull(0)
        val amount = invocation.arguments.getOrNull(1)?.toLongOrNull()
        if (arg == null || amount == null || amount < 0) {
            return ActionResult.error("usage: <player> <amount>")
        }
        val holder = resolve(arg) ?: return ActionResult.error("unknown player: $arg")
        return when (val booking = change(holder, amount)) {
            is Booking.Done -> {
                publish(holder)
                ActionResult.ok("${display(holder)} now has ${booking.balance} crowns")
            }
            Booking.InsufficientFunds -> ActionResult.error("insufficient funds")
            is Booking.LimitReached -> ActionResult.error("limit reached: ${booking.remaining}")
        }
    }

    private companion object {
        /** Crowns every account starts with (design: Spielkonzept A5). */
        const val STARTING_BALANCE = 1000L

        /** Bookings shown by `/kronen verlauf` and returned by default. */
        const val HISTORY_DEFAULT = 20

        /** Upper bound for `eco.api.history`. */
        const val HISTORY_MAX = 200

        /** Bookings included in a data export. */
        const val HISTORY_EXPORT = 1000

        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(BalanceRepository.ZONE)

        /** Whole crowns with German thousands separators (12.340). */
        fun format(amount: Long): String = "%,d".format(java.util.Locale.GERMANY, amount)
    }
}
