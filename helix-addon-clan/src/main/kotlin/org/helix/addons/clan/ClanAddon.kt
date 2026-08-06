package org.helix.addons.clan

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.display.DisplayProfile
import org.helix.api.message.Messages
import java.util.concurrent.ConcurrentHashMap

/**
 * Clan system addon.
 *
 * Provides the in-game `/clan` command (create, info, invite flow, roles,
 * kick/promote/demote, ownership transfer, tag change, shared bank and a
 * clan list), a `[TAG]` display-name suffix via a display resolver (shown
 * in chat, tab and the name tag), and a dashboard panel with the full clan
 * list and admin management (tag, members, ownership, disband). The shared
 * bank moves real coins through the economy addon so its counter never
 * drifts from the players' balances.
 */
class ClanAddon : AddonBase() {
    private val json = Json { prettyPrint = true }
    private lateinit var store: ClanStore
    private lateinit var msg: Messages

    /** Pending invites: lowercase player name to the clan ids they may join. */
    private val invites = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Registers the `/clan` command, the display resolver and admin
     * actions.
     */
    override fun enable() {
        store = ClanStore(context.storage())
        msg = loadMessages()
        // The clan tag is the SUFFIX component of the display name (the prefix belongs to
        // permission groups, the name to the nick addon); the node merges the components.
        // Only VERIFIED tags are displayed — a fresh or renamed tag stays invisible to other
        // players until an admin approves it (clan.verify / panel).
        context.registerDisplayResolver { name ->
            store.clanOf(name)?.takeIf { it.verified }?.let { DisplayProfile(suffix = " &8[&b${it.tag}&8]") }
        }
        // Expose each player's clan tag as a bridge value for the sidebar {clan}
        // placeholder; refreshed on join and after every clan command.
        context.registerPlayerListener(
            /** Publishes the joining player's clan tag for the sidebar `{clan}` placeholder. */
            object : org.helix.api.addon.PlayerListener {
                override fun onJoin(player: org.helix.api.player.OnlinePlayer) = publishTag(player.name)
            },
        )
        context.registerPlayerDataProvider(
            /** Exports the player's clan membership; refuses to delete an owner. */
            object : org.helix.api.addon.PlayerDataProvider {
                override fun export(player: String): String? =
                    store.clanOf(player)?.let { clan ->
                        json.encodeToString(
                            mapOf("clan" to clan.name, "tag" to clan.tag, "role" to clan.members[player.lowercase()].toString()),
                        )
                    }

                override fun delete(player: String): Boolean {
                    // Owners must transfer ownership or disband first — auto-deleting an
                    // owner would leave the clan without a valid owner reference.
                    val clan = store.clanOf(player) ?: return false
                    if (clan.members[player.lowercase()] == ClanRole.OWNER) {
                        return false
                    }
                    return store.removeMember(player)
                }
            },
        )
        action(
            name = "clan",
            description = "Clan system: create, invite, roles, bank and more.",
            usage = "clan <create|info|invite|join|accept|deny|leave|kick|promote|demote|disband|setowner|tag|bank|list>",
            playerCommand = true,
            permission = "helix.clan",
            handler = { invocation ->
                handleClanCommand(invocation).also { invocation.arguments.firstOrNull()?.let(::publishTag) }
            },
        )
        // Clan chat: reachable as /cc and via the @clan chat prefix (the paper bridge forwards
        // "@clan <text>" from regular chat to this action).
        context.registerAction(
            org.helix.api.action.ActionDescriptor(
                name = "cc",
                description = "Sends a message to all online clan members.",
                usage = "cc <message...>",
                playerCommand = true,
                permission = "helix.clan",
            ),
        ) { invocation ->
            val executor = invocation.arguments.firstOrNull()
                ?: return@registerAction ActionResult.error("missing executing player")
            chat(executor, invocation.arguments.drop(1).joinToString(" "))
        }
        action("clan.list", "Lists all clans as JSON (dashboard/panel).", "clan.list") {
            ActionResult.ok(
                json.encodeToString(
                    store.allClans().map { (id, clan) ->
                        ClanSummary(id, clan.name, clan.tag, clan.owner, clan.members.size, clan.bank, clan.verified)
                    },
                ),
            )
        }
        action("clan.info", "Shows a single clan as JSON.", "clan.info <clan>") { invocation ->
            val token = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: clan.info <clan>")
            val id = store.resolveId(token) ?: return@action ActionResult.error("no such clan")
            val clan = store.clanById(id) ?: return@action ActionResult.error("no such clan")
            ActionResult.ok(
                json.encodeToString(
                    ClanSummary(id, clan.name, clan.tag, clan.owner, clan.members.size, clan.bank, clan.verified),
                ),
            )
        }
        action("clan.disband", "Force-disbands a clan (admin).", "clan.disband <clan>") { invocation ->
            val token = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: clan.disband <clan>")
            val id = store.resolveId(token) ?: return@action ActionResult.error("no such clan")
            val affected = store.clanById(id)?.members?.keys?.toList().orEmpty()
            if (store.disband(id)) {
                affected.forEach(::publishTag)
                ActionResult.ok("clan $id disbanded")
            } else {
                ActionResult.error("no such clan")
            }
        }
        action("clan.detail", "Shows a clan with its member list as JSON (panel).", "clan.detail <clan>") { invocation ->
            val token = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: clan.detail <clan>")
            val id = store.resolveId(token) ?: return@action ActionResult.error("no such clan")
            val clan = store.clanById(id) ?: return@action ActionResult.error("no such clan")
            val members = clan.members.entries
                .sortedWith(compareByDescending<Map.Entry<String, ClanRole>> { it.value.rank }.thenBy { it.key })
                .map { ClanMemberEntry(it.key, it.value) }
            ActionResult.ok(
                json.encodeToString(
                    ClanDetail(id, clan.name, clan.tag, clan.owner, clan.bank, clan.createdAtEpochMs, clan.verified, members),
                ),
            )
        }
        action("clan.settag", "Changes a clan's tag (admin).", "clan.settag <clan> <TAG>") { invocation ->
            val token = invocation.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: clan.settag <clan> <TAG>")
            val tag = invocation.arguments.getOrNull(1)
                ?: return@action ActionResult.error("usage: clan.settag <clan> <TAG>")
            val id = store.resolveId(token) ?: return@action ActionResult.error("no such clan")
            if (!TAG_PATTERN.matches(tag)) {
                return@action ActionResult.error("tag must be 2-5 alphanumeric characters")
            }
            if (!store.setTag(id, tag)) {
                return@action ActionResult.error("tag is already taken")
            }
            // An admin-chosen tag is approved by definition; setTag reset the flag.
            store.setVerified(id, true)
            store.clanById(id)?.members?.keys?.forEach(::publishTag)
            ActionResult.ok("tag of $id changed to ${tag.uppercase()} (verified)")
        }
        action("clan.remove", "Removes a member from their clan (admin).", "clan.remove <player>") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: clan.remove <player>")
            val clan = store.clanOf(player) ?: return@action ActionResult.error("$player is in no clan")
            if (clan.owner.equals(player, ignoreCase = true)) {
                return@action ActionResult.error("the owner cannot be removed — transfer ownership or disband")
            }
            store.removeMember(player)
            publishTag(player)
            ActionResult.ok("$player removed from ${clan.name}")
        }
        action("clan.transfer", "Transfers clan ownership (admin).", "clan.transfer <clan> <player>") { invocation ->
            val token = invocation.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: clan.transfer <clan> <player>")
            val player = invocation.arguments.getOrNull(1)
                ?: return@action ActionResult.error("usage: clan.transfer <clan> <player>")
            val id = store.resolveId(token) ?: return@action ActionResult.error("no such clan")
            if (!store.setOwner(id, player)) {
                return@action ActionResult.error("$player is not a member of that clan")
            }
            ActionResult.ok("${player.lowercase()} now owns $id")
        }
        action("clan.verify", "Approves a clan tag for display (admin).", "clan.verify <clan>") { invocation ->
            setVerification(invocation.arguments.firstOrNull(), verified = true)
        }
        action("clan.unverify", "Revokes a clan tag's approval (admin).", "clan.unverify <clan>") { invocation ->
            setVerification(invocation.arguments.firstOrNull(), verified = false)
        }
        panel(
            "clan",
            "Clans",
            "/panel.html",
            "<path d=\"M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 00-3-3.87\"/><path d=\"M16 3.13a4 4 0 010 7.75\"/>",
        )
    }

    /**
     * Publishes a player's clan tag as the `clan.tag.<name>` bridge value —
     * empty when clanless or while the tag awaits verification.
     */
    private fun publishTag(player: String) {
        context.publishBridgeValue(
            "clan.tag.${player.lowercase()}",
            store.clanOf(player)?.takeIf { it.verified }?.tag ?: "",
        )
    }

    private fun handleClanCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val sub = invocation.arguments.getOrNull(1)?.lowercase() ?: "info"
        val rest = invocation.arguments.drop(2)
        return when (sub) {
            "create" -> create(executor, rest)
            "info" -> info(executor, rest.firstOrNull())
            "invite" -> invite(executor, rest.firstOrNull())
            "join", "accept" -> accept(executor, rest.firstOrNull())
            "deny" -> deny(executor, rest.firstOrNull())
            "leave" -> leave(executor)
            "kick" -> kick(executor, rest.firstOrNull())
            "promote" -> promote(executor, rest.firstOrNull())
            "demote" -> demote(executor, rest.firstOrNull())
            "disband" -> disband(executor)
            "setowner" -> setowner(executor, rest.firstOrNull())
            "tag" -> tag(executor, rest.firstOrNull())
            "bank" -> bank(executor, rest)
            "list" -> list(executor)
            else -> usage(executor)
        }
    }

    private fun create(executor: String, args: List<String>): ActionResult {
        val tag = args.getOrNull(0) ?: return ActionResult.error(msg.formatFor(executor, "create.usage"))
        val name = args.drop(1).joinToString(" ")
        if (name.isEmpty()) {
            return ActionResult.error(msg.formatFor(executor, "create.usage"))
        }
        if (store.clanOf(executor) != null) {
            return ActionResult.error(msg.formatFor(executor, "error.inclan"))
        }
        if (!TAG_PATTERN.matches(tag)) {
            return ActionResult.error(msg.formatFor(executor, "error.tagformat"))
        }
        if (name != name.trim() || !NAME_PATTERN.matches(name)) {
            return ActionResult.error(msg.formatFor(executor, "error.nameformat"))
        }
        if (store.tagTaken(tag)) {
            return ActionResult.error(msg.formatFor(executor, "error.tagtaken"))
        }
        if (store.nameTaken(name)) {
            return ActionResult.error(msg.formatFor(executor, "error.nametaken"))
        }
        val clan = store.create(tag, name, executor, System.currentTimeMillis())
            ?: return ActionResult.error(msg.formatFor(executor, "error.inclan"))
        return ActionResult.ok(
            msg.formatFor(executor, "create.ok", "name" to clan.name, "tag" to clan.tag),
            msg.formatFor(executor, "verify.pending"),
        )
    }

    private fun info(executor: String, token: String?): ActionResult {
        val clan = if (token == null) {
            store.clanOf(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        } else {
            store.resolveId(token)?.let { store.clanById(it) }
                ?: return ActionResult.error(msg.formatFor(executor, "error.unknownclan"))
        }
        return ActionResult.ok(
            msg.formatFor(executor, "info.header", "name" to clan.name, "tag" to clan.tag),
            msg.formatFor(executor, "info.owner", "owner" to clan.owner),
            msg.formatFor(executor, "info.members", "count" to clan.members.size.toString()),
            msg.formatFor(executor, "info.bank", "bank" to clan.bank.toString()),
            msg.formatFor(executor, if (clan.verified) "info.verified" else "info.pending"),
        )
    }

    private fun invite(executor: String, target: String?): ActionResult {
        if (target == null) {
            return ActionResult.error(msg.formatFor(executor, "invite.usage"))
        }
        val clanId = store.clanIdOf(executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        if (!canManage(clan, executor)) {
            return ActionResult.error(msg.formatFor(executor, "error.notofficer"))
        }
        if (executor.equals(target, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(executor, "error.selfinvite"))
        }
        if (store.clanOf(target) != null) {
            return ActionResult.error(msg.formatFor(executor, "error.targetinclan", "target" to target))
        }
        invites.getOrPut(target.lowercase()) { ConcurrentHashMap.newKeySet() }.add(clanId)
        message(
            target,
            msg.formatFor(
                target,
                "invite.received",
                "name" to clan.name,
                "tag" to clan.tag,
                "inviter" to executor,
            ),
        )
        return ActionResult.ok(msg.formatFor(executor, "invite.sent", "target" to target))
    }

    private fun accept(executor: String, token: String?): ActionResult {
        if (token == null) {
            return ActionResult.error(msg.formatFor(executor, "accept.usage"))
        }
        val clanId = store.resolveId(token)
            ?: return ActionResult.error(msg.formatFor(executor, "accept.none"))
        val pending = invites[executor.lowercase()]
        if (pending == null || clanId !in pending) {
            return ActionResult.error(msg.formatFor(executor, "accept.none"))
        }
        if (store.clanOf(executor) != null) {
            return ActionResult.error(msg.formatFor(executor, "error.inclan"))
        }
        if (!store.addMember(clanId, executor, ClanRole.MEMBER)) {
            return ActionResult.error(msg.formatFor(executor, "error.unknownclan"))
        }
        invites.remove(executor.lowercase())
        val clan = store.clanById(clanId) ?: return ActionResult.ok(msg.formatFor(executor, "accept.ok", "name" to token))
        broadcast(clan, executor, "member.joined", "player" to executor)
        return ActionResult.ok(msg.formatFor(executor, "accept.ok", "name" to clan.name))
    }

    private fun deny(executor: String, token: String?): ActionResult {
        if (token == null) {
            return ActionResult.error(msg.formatFor(executor, "accept.usage"))
        }
        val clanId = store.resolveId(token)
            ?: return ActionResult.error(msg.formatFor(executor, "accept.none"))
        val pending = invites[executor.lowercase()]
        val removed = pending?.remove(clanId) == true
        if (pending != null && pending.isEmpty()) {
            invites.remove(executor.lowercase())
        }
        if (!removed) {
            return ActionResult.error(msg.formatFor(executor, "accept.none"))
        }
        val name = store.clanById(clanId)?.name ?: token
        return ActionResult.ok(msg.formatFor(executor, "deny.ok", "clan" to name))
    }

    private fun leave(executor: String): ActionResult {
        val clanId = store.clanIdOf(executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        if (clan.owner == executor.lowercase()) {
            return if (clan.members.size == 1) {
                store.disband(clanId)
                ActionResult.ok(msg.formatFor(executor, "leave.disband", "name" to clan.name))
            } else {
                ActionResult.error(msg.formatFor(executor, "leave.owner"))
            }
        }
        store.removeMember(executor)
        return ActionResult.ok(msg.formatFor(executor, "leave.ok", "name" to clan.name))
    }

    private fun kick(executor: String, target: String?): ActionResult {
        if (target == null) {
            return ActionResult.error(msg.formatFor(executor, "kick.usage"))
        }
        val clanId = store.clanIdOf(executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        val actorRole = roleOf(clan, executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        if (actorRole.rank < ClanRole.OFFICER.rank) {
            return ActionResult.error(msg.formatFor(executor, "error.notofficer"))
        }
        val targetRole = clan.members[target.lowercase()]
            ?: return ActionResult.error(msg.formatFor(executor, "error.notmember", "target" to target))
        if (executor.equals(target, ignoreCase = true) || targetRole.rank >= actorRole.rank) {
            return ActionResult.error(msg.formatFor(executor, "error.cantkick"))
        }
        store.removeMember(target)
        message(target, msg.formatFor(target, "kick.notified", "name" to clan.name))
        return ActionResult.ok(msg.formatFor(executor, "kick.ok", "target" to target))
    }

    private fun promote(executor: String, target: String?): ActionResult {
        if (target == null) {
            return ActionResult.error(msg.formatFor(executor, "promote.usage"))
        }
        val clan = ownedClan(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.notowner"))
        val role = clan.members[target.lowercase()]
            ?: return ActionResult.error(msg.formatFor(executor, "error.notmember", "target" to target))
        if (role != ClanRole.MEMBER) {
            return ActionResult.error(msg.formatFor(executor, "promote.already", "target" to target))
        }
        store.setRole(store.clanIdOf(executor)!!, target, ClanRole.OFFICER)
        return ActionResult.ok(msg.formatFor(executor, "promote.ok", "target" to target))
    }

    private fun demote(executor: String, target: String?): ActionResult {
        if (target == null) {
            return ActionResult.error(msg.formatFor(executor, "demote.usage"))
        }
        val clan = ownedClan(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.notowner"))
        val role = clan.members[target.lowercase()]
            ?: return ActionResult.error(msg.formatFor(executor, "error.notmember", "target" to target))
        if (role != ClanRole.OFFICER) {
            return ActionResult.error(msg.formatFor(executor, "demote.already", "target" to target))
        }
        store.setRole(store.clanIdOf(executor)!!, target, ClanRole.MEMBER)
        return ActionResult.ok(msg.formatFor(executor, "demote.ok", "target" to target))
    }

    private fun disband(executor: String): ActionResult {
        val clan = ownedClan(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.notowner"))
        store.disband(store.clanIdOf(executor)!!)
        return ActionResult.ok(msg.formatFor(executor, "disband.ok", "name" to clan.name))
    }

    private fun setowner(executor: String, target: String?): ActionResult {
        if (target == null) {
            return ActionResult.error(msg.formatFor(executor, "setowner.usage"))
        }
        val clanId = store.clanIdOf(executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        if (clan.owner != executor.lowercase()) {
            return ActionResult.error(msg.formatFor(executor, "error.notowner"))
        }
        if (executor.equals(target, ignoreCase = true) || !clan.members.containsKey(target.lowercase())) {
            return ActionResult.error(msg.formatFor(executor, "error.notmember", "target" to target))
        }
        store.setOwner(clanId, target)
        message(target, msg.formatFor(target, "setowner.notified", "name" to clan.name))
        return ActionResult.ok(msg.formatFor(executor, "setowner.ok", "target" to target))
    }

    private fun tag(executor: String, newTag: String?): ActionResult {
        if (newTag == null) {
            return ActionResult.error(msg.formatFor(executor, "tag.usage"))
        }
        val clan = ownedClan(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.notowner"))
        if (!TAG_PATTERN.matches(newTag)) {
            return ActionResult.error(msg.formatFor(executor, "error.tagformat"))
        }
        if (!store.setTag(store.clanIdOf(executor)!!, newTag)) {
            return ActionResult.error(msg.formatFor(executor, "error.tagtaken"))
        }
        // setTag reset the verification; the old (verified) tag must vanish from displays now.
        store.clanById(store.clanIdOf(executor)!!)?.members?.keys?.forEach(::publishTag)
        return ActionResult.ok(
            msg.formatFor(executor, "tag.ok", "tag" to newTag.uppercase(), "name" to clan.name),
            msg.formatFor(executor, "verify.pending"),
        )
    }

    private fun bank(executor: String, args: List<String>): ActionResult {
        val clanId = store.clanIdOf(executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        return when (args.firstOrNull()?.lowercase()) {
            null -> {
                val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
                ActionResult.ok(msg.formatFor(executor, "bank.balance", "bank" to clan.bank.toString()))
            }
            "deposit" -> deposit(executor, clanId, args.getOrNull(1))
            "withdraw" -> withdraw(executor, clanId, args.getOrNull(1))
            else -> ActionResult.error(msg.formatFor(executor, "bank.usage"))
        }
    }

    /**
     * Takes [amount] from the executor's balance and credits the clan bank.
     *
     * The player's coins are only taken once the bank credit is confirmed
     * (via [ClanStore.adjustBank]'s return value); if the credit fails —
     * for example because the clan was disbanded concurrently — the
     * player is refunded instead of the coins being silently destroyed.
     */
    private fun deposit(executor: String, clanId: String, amountArg: String?): ActionResult {
        val amount = amountArg?.toLongOrNull()?.takeIf { it > 0 }
            ?: return ActionResult.error(msg.formatFor(executor, "bank.amount"))
        val take = eco("eco.take", executor, amount)
        if (!take.success) {
            return ActionResult.error(msg.formatFor(executor, "bank.deposit.fail"))
        }
        if (!store.adjustBank(clanId, amount)) {
            eco("eco.give", executor, amount)
            return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        }
        val bank = store.clanById(clanId)?.bank ?: amount
        return ActionResult.ok(
            msg.formatFor(executor, "bank.deposit.ok", "amount" to amount.toString(), "bank" to bank.toString()),
        )
    }

    /**
     * Debits [amount] from the clan bank and pays it out to the executor.
     *
     * The bank is debited FIRST through [ClanStore.adjustBank], which
     * atomically rejects a debit that would take the bank negative; only
     * once that debit actually succeeds is the player paid out. This
     * avoids a coin-duplication race where a stale bank snapshot lets two
     * concurrent withdrawals both pay out before either debit lands. If
     * the payout itself then fails, the debit is undone so the bank's
     * coins are never destroyed either.
     */
    private fun withdraw(executor: String, clanId: String, amountArg: String?): ActionResult {
        val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        if (!canManage(clan, executor)) {
            return ActionResult.error(msg.formatFor(executor, "error.notofficer"))
        }
        val amount = amountArg?.toLongOrNull()?.takeIf { it > 0 }
            ?: return ActionResult.error(msg.formatFor(executor, "bank.amount"))
        if (!store.adjustBank(clanId, -amount)) {
            return ActionResult.error(msg.formatFor(executor, "bank.withdraw.funds"))
        }
        val give = eco("eco.give", executor, amount)
        if (!give.success) {
            store.adjustBank(clanId, amount)
            return ActionResult.error(msg.formatFor(executor, "bank.withdraw.fail"))
        }
        val bank = store.clanById(clanId)?.bank ?: 0
        return ActionResult.ok(
            msg.formatFor(executor, "bank.withdraw.ok", "amount" to amount.toString(), "bank" to bank.toString()),
        )
    }

    private fun list(executor: String): ActionResult {
        val clans = store.allClans()
        if (clans.isEmpty()) {
            return ActionResult.ok(msg.formatFor(executor, "list.empty"))
        }
        return ActionResult.ok(
            *clans.map { (_, clan) ->
                msg.formatFor(
                    executor,
                    "list.entry",
                    "tag" to clan.tag,
                    "name" to clan.name,
                    "count" to clan.members.size.toString(),
                )
            }.toTypedArray(),
        )
    }

    private fun usage(executor: String): ActionResult =
        ActionResult.error(msg.formatFor(executor, "usage"))

    private fun ownedClan(executor: String): Clan? {
        val clan = store.clanOf(executor) ?: return null
        return clan.takeIf { it.owner == executor.lowercase() }
    }

    private fun roleOf(clan: Clan, player: String): ClanRole? = clan.members[player.lowercase()]

    private fun canManage(clan: Clan, player: String): Boolean =
        roleOf(clan, player)?.let { it.rank >= ClanRole.OFFICER.rank } == true

    /**
     * Applies a verification decision: gates the tag display, refreshes the
     * members' bridge values and tells online members about an approval.
     */
    private fun setVerification(token: String?, verified: Boolean): ActionResult {
        val input = token ?: return ActionResult.error("usage: clan.${if (verified) "verify" else "unverify"} <clan>")
        val id = store.resolveId(input) ?: return ActionResult.error("no such clan")
        val clan = store.clanById(id) ?: return ActionResult.error("no such clan")
        store.setVerified(id, verified)
        clan.members.keys.forEach(::publishTag)
        if (verified) {
            broadcast(clan, except = "", key = "verify.notified", "name" to clan.name, "tag" to clan.tag)
        }
        return ActionResult.ok("clan $id ${if (verified) "verified — tag is now visible" else "unverified — tag is hidden"}")
    }

    /** Delivers a clan-chat line to every online member (including the sender). */
    private fun chat(executor: String, text: String): ActionResult {
        if (text.isBlank()) {
            return ActionResult.error(msg.formatFor(executor, "chat.usage"))
        }
        val clan = store.clanOf(executor)
            ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
        clan.members.keys
            .filter { it in online }
            .forEach { member ->
                message(member, msg.formatFor(member, "chat", "tag" to clan.tag, "sender" to executor, "message" to text))
            }
        return ActionResult.ok()
    }

    private fun broadcast(clan: Clan, except: String, key: String, vararg params: Pair<String, String>) {
        val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
        clan.members.keys
            .filter { it != except.lowercase() && it in online }
            .forEach { member -> message(member, msg.formatFor(member, key, *params)) }
    }

    private fun eco(action: String, player: String, amount: Long): ActionResult =
        context.actions.invoke(
            ActionInvocation(action, listOf(player, amount.toString()), ActionSource.ADDON),
        )

    private fun message(player: String, text: String) {
        context.actions.invoke(
            ActionInvocation("player.message", listOf(player, text), ActionSource.ADDON),
        )
    }

    private companion object {
        /** Valid clan tag: 2-5 alphanumeric characters. */
        val TAG_PATTERN = Regex("[A-Za-z0-9]{2,5}")

        /**
         * Valid clan name: 3-24 characters, no control characters and no `&`
         * or `§` (both trigger a Minecraft color/formatting code, which
         * would let a clan name inject markup into chat, tab and the name
         * tag suffix). Leading/trailing whitespace is rejected separately.
         */
        val NAME_PATTERN = Regex("[^\\p{Cntrl}&§]{3,24}")
    }
}
