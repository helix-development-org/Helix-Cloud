package org.helix.addons.clan

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.display.DisplayProfile
import org.helix.api.message.Messages

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
        msg = context.localizedMessages(messages())
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
        val name = args.drop(1).joinToString(" ").trim()
        if (name.isEmpty()) {
            return ActionResult.error(msg.formatFor(executor, "create.usage"))
        }
        if (store.clanOf(executor) != null) {
            return ActionResult.error(msg.formatFor(executor, "error.inclan"))
        }
        if (!TAG_PATTERN.matches(tag)) {
            return ActionResult.error(msg.formatFor(executor, "error.tagformat"))
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

    private fun deposit(executor: String, clanId: String, amountArg: String?): ActionResult {
        val amount = amountArg?.toLongOrNull()?.takeIf { it > 0 }
            ?: return ActionResult.error(msg.formatFor(executor, "bank.amount"))
        val take = eco("eco.take", executor, amount)
        if (!take.success) {
            return ActionResult.error(msg.formatFor(executor, "bank.deposit.fail"))
        }
        store.adjustBank(clanId, amount)
        val bank = store.clanById(clanId)?.bank ?: amount
        return ActionResult.ok(
            msg.formatFor(executor, "bank.deposit.ok", "amount" to amount.toString(), "bank" to bank.toString()),
        )
    }

    private fun withdraw(executor: String, clanId: String, amountArg: String?): ActionResult {
        val clan = store.clanById(clanId) ?: return ActionResult.error(msg.formatFor(executor, "error.noclan"))
        if (!canManage(clan, executor)) {
            return ActionResult.error(msg.formatFor(executor, "error.notofficer"))
        }
        val amount = amountArg?.toLongOrNull()?.takeIf { it > 0 }
            ?: return ActionResult.error(msg.formatFor(executor, "bank.amount"))
        if (clan.bank < amount) {
            return ActionResult.error(msg.formatFor(executor, "bank.withdraw.funds"))
        }
        val give = eco("eco.give", executor, amount)
        if (!give.success) {
            return ActionResult.error(msg.formatFor(executor, "bank.withdraw.fail"))
        }
        store.adjustBank(clanId, -amount)
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

    private fun messages(): Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "create.ok" to "&aClan &f{name} &acreated with tag &f[{tag}]&a.",
            "chat" to "&8[&b{tag}&8] &f{sender}&7: &f{message}",
            "chat.usage" to "&cUsage: /cc \\<message...> &7— or write &f@clan \\<message> &7in chat",
            "create.usage" to "&cUsage: /clan create \\<tag> \\<name...>",
            "info.header" to "&8— &b{name} &7[&b{tag}&7] &8—",
            "info.owner" to "&7Owner: &f{owner}",
            "info.members" to "&7Members: &f{count}",
            "info.bank" to "&7Bank: &f{bank} &7coins",
            "invite.usage" to "&cUsage: /clan invite \\<player>",
            "invite.sent" to "&aInvited &f{target} &ato your clan.",
            "invite.received" to "&e{inviter} invited you to &f{name}&e. <click:run_command:'/clan accept {tag}'><green>[accept]</green></click>",
            "accept.usage" to "&cUsage: /clan accept \\<clan>",
            "accept.ok" to "&aYou joined &f{name}&a.",
            "accept.none" to "&cYou have no invite from that clan.",
            "member.joined" to "&a{player} &7joined the clan.",
            "deny.ok" to "&7Denied the invite from {clan}.",
            "leave.ok" to "&7You left &f{name}&7.",
            "leave.disband" to "&7You left and disbanded &f{name}&7.",
            "leave.owner" to "&cAs owner you must transfer ownership or disband first.",
            "kick.usage" to "&cUsage: /clan kick \\<player>",
            "kick.ok" to "&aKicked &f{target} &afrom the clan.",
            "kick.notified" to "&cYou were kicked from &f{name}&c.",
            "promote.usage" to "&cUsage: /clan promote \\<player>",
            "promote.ok" to "&a{target} is now an officer.",
            "promote.already" to "&c{target} cannot be promoted further.",
            "demote.usage" to "&cUsage: /clan demote \\<player>",
            "demote.ok" to "&a{target} is now a member.",
            "demote.already" to "&c{target} cannot be demoted further.",
            "disband.ok" to "&7Clan &f{name} &7disbanded.",
            "setowner.usage" to "&cUsage: /clan setowner \\<player>",
            "setowner.ok" to "&a{target} is now the clan owner.",
            "setowner.notified" to "&aYou are now the owner of &f{name}&a.",
            "tag.usage" to "&cUsage: /clan tag \\<TAG>",
            "tag.ok" to "&aTag changed to &f[{tag}]&a.",
            "verify.pending" to "&7The tag is &epending verification &7— it becomes visible to other players once a team member approves it.",
            "verify.notified" to "&aYour clan &f{name} &awas verified — the tag &f[{tag}] &ais now visible.",
            "info.verified" to "&7Status: &averified",
            "info.pending" to "&7Status: &epending verification",
            "bank.usage" to "&cUsage: /clan bank [deposit|withdraw \\<amount>]",
            "bank.balance" to "&7Clan bank: &f{bank} &7coins.",
            "bank.amount" to "&cAmount must be a positive number.",
            "bank.deposit.ok" to "&aDeposited &f{amount} &acoins. Bank: &f{bank}&a.",
            "bank.deposit.fail" to "&cYou do not have enough coins.",
            "bank.withdraw.ok" to "&aWithdrew &f{amount} &acoins. Bank: &f{bank}&a.",
            "bank.withdraw.funds" to "&cThe clan bank does not have enough coins.",
            "bank.withdraw.fail" to "&cWithdrawal failed.",
            "list.empty" to "&7No clans exist yet. &f/clan create \\<tag> \\<name...>",
            "list.entry" to "&f[{tag}] {name} &7— {count} members",
            "error.inclan" to "&cYou are already in a clan.",
            "error.noclan" to "&cYou are not in a clan.",
            "error.unknownclan" to "&cNo such clan.",
            "error.tagtaken" to "&cThat tag is already taken.",
            "error.nametaken" to "&cThat name is already taken.",
            "error.tagformat" to "&cThe tag must be 2-5 alphanumeric characters.",
            "error.notofficer" to "&cYou must be an officer or the owner.",
            "error.notowner" to "&cOnly the owner can do that.",
            "error.notmember" to "&c{target} is not in your clan.",
            "error.cantkick" to "&cYou cannot kick that member.",
            "error.selfinvite" to "&cYou cannot invite yourself.",
            "error.targetinclan" to "&c{target} is already in a clan.",
            "usage" to "&cUsage: /clan \\<create|info|invite|join|accept|deny|leave|kick|promote|demote|disband|setowner|tag|bank|list>",
        ),
        "de" to mapOf(
            "create.ok" to "&aClan &f{name} &amit Tag &f[{tag}] &aerstellt.",
            "chat" to "&8[&b{tag}&8] &f{sender}&7: &f{message}",
            "chat.usage" to "&cVerwendung: /cc \\<nachricht...> &7— oder schreibe &f@clan \\<nachricht> &7in den Chat",
            "create.usage" to "&cVerwendung: /clan create \\<tag> \\<name...>",
            "info.header" to "&8— &b{name} &7[&b{tag}&7] &8—",
            "info.owner" to "&7Besitzer: &f{owner}",
            "info.members" to "&7Mitglieder: &f{count}",
            "info.bank" to "&7Bank: &f{bank} &7Coins",
            "invite.usage" to "&cVerwendung: /clan invite \\<player>",
            "invite.sent" to "&a{target} &awurde in deinen Clan eingeladen.",
            "invite.received" to "&e{inviter} hat dich zu &f{name} &eeingeladen. <click:run_command:'/clan accept {tag}'><green>[annehmen]</green></click>",
            "accept.usage" to "&cVerwendung: /clan accept \\<clan>",
            "accept.ok" to "&aDu bist &f{name} &abeigetreten.",
            "accept.none" to "&cDu hast keine Einladung von diesem Clan.",
            "member.joined" to "&a{player} &7ist dem Clan beigetreten.",
            "deny.ok" to "&7Einladung von {clan} abgelehnt.",
            "leave.ok" to "&7Du hast &f{name} &7verlassen.",
            "leave.disband" to "&7Du hast &f{name} &7verlassen und aufgelöst.",
            "leave.owner" to "&cAls Besitzer musst du erst die Leitung übergeben oder auflösen.",
            "kick.usage" to "&cVerwendung: /clan kick \\<player>",
            "kick.ok" to "&a{target} &awurde aus dem Clan geworfen.",
            "kick.notified" to "&cDu wurdest aus &f{name} &cgeworfen.",
            "promote.usage" to "&cVerwendung: /clan promote \\<player>",
            "promote.ok" to "&a{target} ist jetzt Offizier.",
            "promote.already" to "&c{target} kann nicht weiter befördert werden.",
            "demote.usage" to "&cVerwendung: /clan demote \\<player>",
            "demote.ok" to "&a{target} ist jetzt Mitglied.",
            "demote.already" to "&c{target} kann nicht weiter herabgestuft werden.",
            "disband.ok" to "&7Clan &f{name} &7aufgelöst.",
            "setowner.usage" to "&cVerwendung: /clan setowner \\<player>",
            "setowner.ok" to "&a{target} ist jetzt der Clan-Besitzer.",
            "setowner.notified" to "&aDu bist jetzt der Besitzer von &f{name}&a.",
            "tag.usage" to "&cVerwendung: /clan tag \\<TAG>",
            "tag.ok" to "&aTag geändert zu &f[{tag}]&a.",
            "verify.pending" to "&7Der Tag ist &enoch nicht verifiziert &7— er wird für andere Spieler sichtbar, sobald ein Teammitglied ihn freigibt.",
            "verify.notified" to "&aEuer Clan &f{name} &awurde verifiziert — der Tag &f[{tag}] &aist jetzt sichtbar.",
            "info.verified" to "&7Status: &averifiziert",
            "info.pending" to "&7Status: &ewartet auf Verifizierung",
            "bank.usage" to "&cVerwendung: /clan bank [deposit|withdraw \\<amount>]",
            "bank.balance" to "&7Clan-Bank: &f{bank} &7Coins.",
            "bank.amount" to "&cDer Betrag muss eine positive Zahl sein.",
            "bank.deposit.ok" to "&a{amount} &aCoins eingezahlt. Bank: &f{bank}&a.",
            "bank.deposit.fail" to "&cDu hast nicht genug Coins.",
            "bank.withdraw.ok" to "&a{amount} &aCoins abgehoben. Bank: &f{bank}&a.",
            "bank.withdraw.funds" to "&cDie Clan-Bank hat nicht genug Coins.",
            "bank.withdraw.fail" to "&cAbhebung fehlgeschlagen.",
            "list.empty" to "&7Es gibt noch keine Clans. &f/clan create \\<tag> \\<name...>",
            "list.entry" to "&f[{tag}] {name} &7— {count} Mitglieder",
            "error.inclan" to "&cDu bist bereits in einem Clan.",
            "error.noclan" to "&cDu bist in keinem Clan.",
            "error.unknownclan" to "&cKein solcher Clan.",
            "error.tagtaken" to "&cDieser Tag ist bereits vergeben.",
            "error.nametaken" to "&cDieser Name ist bereits vergeben.",
            "error.tagformat" to "&cDer Tag muss 2-5 alphanumerische Zeichen haben.",
            "error.notofficer" to "&cDu musst Offizier oder Besitzer sein.",
            "error.notowner" to "&cNur der Besitzer kann das tun.",
            "error.notmember" to "&c{target} ist nicht in deinem Clan.",
            "error.cantkick" to "&cDieses Mitglied kannst du nicht entfernen.",
            "error.selfinvite" to "&cDu kannst dich nicht selbst einladen.",
            "error.targetinclan" to "&c{target} ist bereits in einem Clan.",
            "usage" to "&cVerwendung: /clan \\<create|info|invite|join|accept|deny|leave|kick|promote|demote|disband|setowner|tag|bank|list>",
        ),
    )

    private companion object {
        /** Valid clan tag: 2-5 alphanumeric characters. */
        val TAG_PATTERN = Regex("[A-Za-z0-9]{2,5}")
    }
}
