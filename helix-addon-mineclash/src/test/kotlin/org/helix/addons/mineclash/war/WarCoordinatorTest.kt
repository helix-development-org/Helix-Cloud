package org.helix.addons.mineclash.war

import org.helix.addons.mineclash.clan.ClanSource
import org.helix.addons.mineclash.clan.ClanView
import org.helix.api.player.OnlinePlayer
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarCoordinatorTest {
    private class FakeClans : ClanSource {
        val clans = linkedMapOf<String, ClanView>()
        var up = true

        fun add(id: String, tag: String, members: Map<String, String>) {
            clans[id] = ClanView(id, id.replaceFirstChar { it.uppercase() }, tag, members.mapKeys { it.key.lowercase() })
        }

        override fun clanIdOf(player: String): String? = clans.values.firstOrNull { it.has(player) }?.id

        override fun detail(token: String): ClanView? =
            clans[token.lowercase()] ?: clans.values.firstOrNull { it.tag.equals(token, true) || it.name.equals(token, true) }

        override fun reachable(): Boolean = up
    }

    private var now = 1_792_700_000_000L
    private val clans = FakeClans().apply {
        add("kronritter", "KRON", mapOf("Alice" to "OWNER", "Anna" to "OFFICER", "Arne" to "MEMBER", "Abel" to "MEMBER"))
        add("zulus", "ZULU", mapOf("Bob" to "OWNER", "Bea" to "MEMBER", "Ben" to "MEMBER"))
        add("drachen", "DRA", mapOf("Carl" to "OWNER"))
    }
    private val online = mutableListOf<OnlinePlayer>()
    private var settings = WarSettings(maxPlayersPerClan = 2)
    private val storage = InMemoryAddonStorage()
    private var store: WarStore = DocumentWarStore(storage)
    private var coordinator = newCoordinator()

    private fun newCoordinator() = WarCoordinator(store, clans, { online.toList() }, { "uuid-${it.lowercase()}" }, { settings }, { "MineClash-Game-1" }, { now })

    private fun online(vararg names: String) {
        names.forEach { name -> online += OnlinePlayer(name, "uuid-${name.lowercase()}") }
    }

    private fun keys(outcome: Outcome) = outcome.replies.map { it.key }

    private fun messagesTo(effects: List<WarEffect>, player: String) =
        effects.filterIsInstance<WarEffect.Message>().filter { it.player == player }.map { it.msg.key }

    private fun status(bootId: String = "boot-1", ready: Int = 2, capacity: Int = 3, wars: List<HostedWar> = emptyList(), rejected: List<String> = emptyList()) =
        WarServerStatus(bootId, capacity, PoolStatus(ready, 2, false, 0.0), 20.0, wars, rejected)

    private fun acceptedWar(): WarRecord {
        online("Alice", "Bob", "Arne", "Bea")
        coordinator.challenge("Alice", "ZULU")
        coordinator.accept("Bob", "KRON")
        return coordinator.active().single()
    }

    @Test
    fun `an officer challenges and the other clan's online officers get a clickable challenge`() {
        online("Alice", "Arne", "Bob")
        val outcome = coordinator.challenge("Alice", "zulu")

        assertEquals(listOf("clanwar.challenge.sent"), keys(outcome))
        assertEquals("5", outcome.replies.single().params["minutes"])
        assertEquals(listOf("clanwar.challenge.received"), messagesTo(outcome.effects, "Bob"))
        assertEquals(listOf("clanwar.challenge.clan_info"), messagesTo(outcome.effects, "Arne"))
        assertTrue(outcome.effects.any { it is WarEffect.StartService }, "no war server alive: start one already on the challenge")
    }

    @Test
    fun `rank, clan and target checks`() {
        online("Alice", "Arne", "Bob", "Carl")
        assertEquals(listOf("clanwar.no_rank"), keys(coordinator.challenge("Arne", "ZULU")))
        assertEquals(listOf("clanwar.no_clan"), keys(coordinator.challenge("Nobody", "ZULU")))
        assertEquals(listOf("clanwar.unknown_clan"), keys(coordinator.challenge("Alice", "NOPE")))
        assertEquals(listOf("clanwar.self"), keys(coordinator.challenge("Alice", "KRON")))
        assertEquals(listOf("clanwar.challenge.sent"), keys(coordinator.challenge("Alice", "ZULU")))
        assertEquals(listOf("clanwar.busy_own"), keys(coordinator.challenge("Anna", "DRA")), "one outgoing challenge per clan")
        assertEquals(listOf("clanwar.no_rank"), keys(coordinator.accept("Bea", "KRON")))
        assertEquals(listOf("clanwar.usage"), keys(coordinator.command("Alice", listOf("challenge"))))
    }

    @Test
    fun `no officer online refuses immediately and blocks nothing`() {
        online("Alice")
        assertEquals(listOf("clanwar.no_officer_online"), keys(coordinator.challenge("Alice", "ZULU")))
        assertEquals(listOf("clanwar.status.header", "clanwar.status.none"), keys(coordinator.status("Alice")))
    }

    @Test
    fun `accepting starts a queued war and supersedes the other challenges of both clans`() {
        online("Alice", "Bob", "Carl")
        coordinator.challenge("Carl", "KRON")
        coordinator.challenge("Alice", "ZULU")
        val outcome = coordinator.accept("Bob", "KRON")

        val war = coordinator.active().single()
        assertEquals(WarState.QUEUED, war.state)
        assertEquals(listOf("kronritter", "zulus"), war.teams.map { it.clanId })
        assertEquals("A", war.teamOf("kronritter")?.id)
        assertTrue(war.warId.matches(Regex("w-\\d{8}-0001")))
        assertTrue("clanwar.accepted" in messagesTo(outcome.effects, "Alice"))
        assertTrue("clanwar.challenge.superseded" in messagesTo(outcome.effects, "Alice"), "Kronritter's incoming challenge from Drachen is dropped")
        assertTrue("clanwar.challenge.expired" in messagesTo(outcome.effects, "Carl"))
        assertTrue("clanwar.service_starting" in messagesTo(outcome.effects, "Bob"))
        assertEquals(listOf("clanwar.challenge.none"), keys(coordinator.accept("Alice", "DRA")))
        assertEquals(listOf("clanwar.busy_target"), keys(coordinator.challenge("Carl", "ZULU")))
        assertEquals(listOf("clanwar.busy_own"), keys(coordinator.challenge("Anna", "DRA")))
    }

    @Test
    fun `a counter challenge counts as acceptance`() {
        online("Alice", "Bob")
        coordinator.challenge("Alice", "ZULU")
        val outcome = coordinator.challenge("Bob", "KRON")

        assertTrue(outcome.replies.isEmpty())
        assertEquals("A", coordinator.active().single().teamOf("kronritter")?.id, "the first challenger stays team A")
    }

    @Test
    fun `deny, withdraw and expiry`() {
        online("Alice", "Anna", "Bob", "Carl")
        coordinator.challenge("Alice", "ZULU")
        val deny = coordinator.deny("Bob", "KRON")
        assertEquals(listOf("clanwar.challenge.deny_done"), keys(deny))
        assertEquals(listOf("clanwar.challenge.denied"), messagesTo(deny.effects, "Alice"))
        assertEquals(listOf("clanwar.challenge.none"), keys(coordinator.deny("Bob", "KRON")))

        coordinator.challenge("Alice", "ZULU")
        val withdraw = coordinator.cancel("Anna")
        assertEquals(listOf("clanwar.challenge.cancelled"), keys(withdraw))
        assertEquals(listOf("clanwar.challenge.withdrawn"), messagesTo(withdraw.effects, "Bob"))

        coordinator.challenge("Carl", "ZULU")
        now += 301_000
        assertEquals(listOf("clanwar.challenge.none"), keys(coordinator.accept("Bob", "DRA")), "expired challenges cannot be accepted")
        val tick = coordinator.tick()
        assertEquals(listOf("clanwar.challenge.expired"), messagesTo(tick, "Carl"))
        assertEquals(listOf("clanwar.challenge.expired"), messagesTo(tick, "Bob"))
        assertEquals(listOf("clanwar.cancel.none"), keys(coordinator.cancel("Carl")))
    }

    @Test
    fun `a polling server with a ready spare gets the war, reports preparation and running`() {
        val war = acceptedWar()
        val first = coordinator.poll("MineClash-War-1", status())
        assertEquals(WarState.ASSIGNED, coordinator.active().single().state)
        val spec = first.state.wars.single()
        assertEquals(war.warId, spec.warId)
        assertEquals(120, spec.prepareSeconds)
        assertEquals(listOf("A", "B"), spec.teams.map { it.id })
        assertEquals("MineClash-Game-1", first.state.returnTo)

        val prepare = coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "PREPARE", now + 120_000))))
        assertEquals(WarState.PREPARE, coordinator.active().single().state)
        assertEquals(listOf("clanwar.ready"), messagesTo(prepare.effects, "Alice"))
        assertEquals("120", prepare.effects.filterIsInstance<WarEffect.Message>().first().msg.params["seconds"])

        now += 120_000
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING", points = mapOf("A" to 20, "B" to 0), present = mapOf("A" to 1)))))
        assertEquals(WarState.RUNNING, coordinator.active().single().state)
        assertEquals(listOf("clanwar.status.header", "clanwar.status.war"), keys(coordinator.status("Alice")))
        assertEquals(listOf("clanwar.cancel.running"), keys(coordinator.cancel("Alice")))
    }

    @Test
    fun `without a ready spare the war waits and a full server triggers a second one, with cooldown`() {
        acceptedWar()
        val busy = coordinator.poll("MineClash-War-1", status(ready = 0))
        assertEquals(WarState.QUEUED, coordinator.active().single().state)
        assertTrue(busy.effects.none { it is WarEffect.StartService }, "a server is alive and only generating: wait for it")
        assertEquals(listOf("clanwar.status.header", "clanwar.status.queued"), keys(coordinator.status("Alice")))

        val full = coordinator.poll("MineClash-War-1", status(ready = 2, capacity = 1, wars = listOf(HostedWar("w-other", "RUNNING"))))
        assertEquals(WarState.ASSIGNED, coordinator.active().single().state, "the foreign war is not counted, the node does not know it")
        assertTrue(full.effects.none { it is WarEffect.StartService })
    }

    @Test
    fun `starting further servers respects the cooldown`() {
        online("Alice", "Bob", "Carl")
        clans.add("eulen", "EUL", mapOf("Eve" to "OWNER"))
        online("Eve")
        coordinator.challenge("Alice", "ZULU")
        coordinator.accept("Bob", "KRON")
        coordinator.poll("MineClash-War-1", status(capacity = 1))
        val first = coordinator.active().single().warId
        coordinator.poll("MineClash-War-1", status(capacity = 1, wars = listOf(HostedWar(first, "PREPARE", now + 120_000))))
        now += 121_000
        coordinator.poll("MineClash-War-1", status(capacity = 1, wars = listOf(HostedWar(first, "RUNNING"))))
        coordinator.challenge("Carl", "EUL")
        val second = coordinator.accept("Eve", "DRA")
        assertTrue(second.effects.any { it is WarEffect.StartService }, "the only server is full")
        coordinator.poll("MineClash-War-1", status(capacity = 1, wars = listOf(HostedWar(first, "RUNNING"))))
        now += 60_000
        assertTrue(coordinator.tick().none { it is WarEffect.StartService }, "cooldown")
        assertEquals(WarState.QUEUED, coordinator.active().first { it.warId != first }.state)
    }

    @Test
    fun `rejection and missing confirmation put the war back into the queue and skip that server`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(rejected = listOf(war.warId)))
        assertEquals(WarState.QUEUED, coordinator.active().single().state)
        assertTrue(coordinator.poll("MineClash-War-1", status()).state.wars.isEmpty(), "server skipped for 60 s")
        coordinator.poll("MineClash-War-2", status(bootId = "boot-2"))
        assertEquals("MineClash-War-2", coordinator.active().single().serviceId)

        now += 31_000
        coordinator.poll("MineClash-War-2", status(bootId = "boot-2"))
        assertEquals(WarState.QUEUED, coordinator.active().single().state, "not confirmed within 30 s")
    }

    @Test
    fun `a silent server or a new boot cancels its wars without reward`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "PREPARE", now + 120_000))))
        val restart = coordinator.poll("MineClash-War-1", status(bootId = "boot-new"))
        assertTrue(coordinator.active().isEmpty())
        assertEquals(WarState.CANCELLED, store.load(war.warId)?.state)
        assertEquals(WarProtocol.CancelReason.SERVER_LOST, store.load(war.warId)?.cancelReason)
        val cancelled = restart.effects.filterIsInstance<WarEffect.Message>().first { it.player == "Alice" }.msg
        assertEquals("clanwar.cancelled", cancelled.key)
        assertEquals("clanwar.cancel_reason.SERVER_LOST", cancelled.refs["reason"])

        val second = run {
            coordinator.challenge("Alice", "ZULU")
            coordinator.accept("Bob", "KRON")
            coordinator.active().single()
        }
        coordinator.poll("MineClash-War-1", status(bootId = "boot-new"))
        coordinator.poll("MineClash-War-1", status(bootId = "boot-new", wars = listOf(HostedWar(second.warId, "RUNNING"))))
        now += 61_000
        coordinator.tick()
        assertTrue(coordinator.active().isEmpty(), "server lost after 60 s of silence")
    }

    @Test
    fun `queued wars time out`() {
        val war = acceptedWar()
        now += 10 * 60_000L + 1
        coordinator.tick()
        assertEquals(WarProtocol.CancelReason.QUEUE_TIMEOUT, store.load(war.warId)?.cancelReason)
    }

    @Test
    fun `joining respects state, membership and the roster limit`() {
        val war = acceptedWar()
        assertEquals(listOf("clanwar.join.not_ready"), keys(coordinator.join("Arne")))
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "PREPARE", now + 120_000))))

        val join = coordinator.join("Arne")
        assertEquals(listOf("clanwar.join.connecting"), keys(join))
        assertEquals(WarEffect.Connect("Arne", "MineClash-War-1"), join.effects.single())
        assertEquals(listOf("Arne"), coordinator.active().single().teamOf("kronritter")?.members?.map { it.name })
        assertEquals(listOf("clanwar.join.connecting"), keys(coordinator.join("Arne")), "joining again does not count twice")
        coordinator.join("Alice")
        assertEquals(listOf("clanwar.join.full"), keys(coordinator.join("Anna")))
        assertEquals(listOf("clanwar.no_clan"), keys(coordinator.join("Nobody")))
        assertEquals(listOf("clanwar.join.no_war"), keys(coordinator.join("Carl")))
    }

    @Test
    fun `arrivals admit rostered players, roster members within the limit and refuse the rest`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        assertEquals("clanwar.join.not_ready", coordinator.arrive("MineClash-War-1", "uuid-bob", "Bob").refusal?.key)
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING"))))

        val bob = coordinator.arrive("MineClash-War-1", "uuid-bob", "Bob")
        assertEquals(war.warId, bob.warId)
        assertEquals("B", bob.team)
        assertNull(bob.refusal)
        coordinator.arrive("MineClash-War-1", "uuid-bea", "Bea")
        assertEquals("clanwar.join.full", coordinator.arrive("MineClash-War-1", "uuid-ben", "Ben").refusal?.key)
        assertEquals("B", coordinator.arrive("MineClash-War-1", "uuid-bob", "Bob").team, "rostered players always come back")
        assertEquals("war.arrive.no_war", coordinator.arrive("MineClash-War-1", "uuid-carl", "Carl").refusal?.key)
        assertEquals("war.arrive.no_war", coordinator.arrive("MineClash-War-2", "uuid-bob", "Bob").refusal?.key)
        val desired = coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING")))).state
        assertEquals(listOf("Bob", "Bea"), desired.wars.single().teams[1].members.map { it.name })
    }

    @Test
    fun `members who left the clan leave the roster, a disbanded clan cancels the war`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING"))))
        coordinator.join("Arne")
        clans.add("kronritter", "KRON", mapOf("Alice" to "OWNER"))
        now += 31_000
        val effects = coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING")))).effects
        assertEquals(listOf("clanwar.member_removed"), messagesTo(effects, "Arne"))
        assertTrue(coordinator.active().single().teamOf("kronritter")!!.members.isEmpty())

        clans.up = false
        clans.clans.remove("zulus")
        now += 31_000
        coordinator.tick()
        assertEquals(1, coordinator.active().size, "clan addon unreachable: nothing is cancelled")
        clans.up = true
        now += 31_000
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING"))))
        assertEquals(WarProtocol.CancelReason.DISBANDED, store.load(war.warId)?.cancelReason)
        assertEquals(listOf(WarCancel(war.warId, "DISBANDED")), coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING")))).state.cancel)
    }

    @Test
    fun `results are stored once, paid and announced`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING"))))
        val result = WarResult(
            war.warId, "A", WarProtocol.EndReason.POINT_LIMIT, mapOf("A" to 5000, "B" to 3120), now, now + 60_000,
            listOf(WarParticipant("uuid-alice", "Alice", "A", 4, 1, true), WarParticipant("uuid-bob", "Bob", "B", 1, 4, true)),
        )
        val first = coordinator.finish("MineClash-War-1", result)
        assertEquals(WarCoordinator.RECORDED, first.answer)
        val pay = first.effects.filterIsInstance<WarEffect.PayWar>().single()
        assertEquals(mapOf("uuid-alice" to "A", "uuid-bob" to "B"), pay.players)
        assertEquals("A", pay.winner)
        val won = first.effects.filterIsInstance<WarEffect.Message>().first { it.player == "Bob" }.msg
        assertEquals("clanwar.result.won", won.key)
        assertEquals("KRON", won.params["tag"])
        assertEquals("5000 : 3120", won.params["points"])
        assertEquals(WarState.FINISHED, store.load(war.warId)?.state)
        assertTrue(coordinator.active().isEmpty())

        assertEquals(WarCoordinator.DUPLICATE, coordinator.finish("MineClash-War-1", result).answer)
        assertEquals(WarCoordinator.DUPLICATE, coordinator.finish("MineClash-War-1", result.copy(warId = "w-unknown")).answer)
        assertEquals(listOf("clanwar.challenge.sent"), keys(coordinator.challenge("Alice", "ZULU")), "both clans are free again")
    }

    @Test
    fun `cancelling before the start reaches the server, staff can cancel any war`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "PREPARE", now + 60_000))))
        val cancel = coordinator.cancel("Anna")
        assertTrue(cancel.replies.isEmpty())
        assertEquals("clanwar.cancelled", messagesTo(cancel.effects, "Bob").single())
        val desired = coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "PREPARE", now + 60_000)))).state
        assertTrue(desired.wars.isEmpty())
        assertEquals(listOf(WarCancel(war.warId, "CLAN")), desired.cancel)
        assertTrue(coordinator.poll("MineClash-War-1", status()).state.cancel.isEmpty(), "dropped once the server no longer hosts it")

        coordinator.challenge("Alice", "ZULU")
        coordinator.accept("Bob", "KRON")
        val second = coordinator.active().single()
        assertEquals(listOf("war ${second.warId} cancelled"), coordinator.adminCancel(second.warId).replies.map { it.key })
        assertEquals(WarProtocol.CancelReason.ADMIN, store.load(second.warId)?.cancelReason)
    }

    @Test
    fun `a node restart reloads wars and cancels those their server does not report`() {
        val war = acceptedWar()
        coordinator.poll("MineClash-War-1", status())
        coordinator.poll("MineClash-War-1", status(wars = listOf(HostedWar(war.warId, "RUNNING"))))
        coordinator = newCoordinator()
        assertEquals(WarState.RUNNING, coordinator.active().single().state)
        coordinator.poll("MineClash-War-1", status())
        now += 61_000
        coordinator.poll("MineClash-War-1", status())
        assertEquals(WarProtocol.CancelReason.SERVER_LOST, store.load(war.warId)?.cancelReason)
    }

    @Test
    fun `officers coming online are reminded of open challenges`() {
        online("Alice")
        clans.add("zulus", "ZULU", mapOf("Bob" to "OWNER", "Bea" to "MEMBER", "Ben" to "OFFICER"))
        online("Ben")
        coordinator.challenge("Alice", "ZULU")
        assertEquals(listOf("clanwar.challenge.reminder"), coordinator.onJoin("Bob").map { (it as WarEffect.Message).msg.key })
        assertTrue(coordinator.onJoin("Bea").isEmpty())
        assertTrue(coordinator.onJoin("Carl").isEmpty())
    }

    @Test
    fun `war ids count per day and survive a new coordinator`() {
        assertEquals("w-20261022-0001", store.nextId("20261022"))
        store.save(WarRecord("w-20261022-0001", WarState.FINISHED, emptyList(), createdAt = 0, acceptedAt = 0))
        store.save(WarRecord("w-20261022-0007", WarState.QUEUED, emptyList(), createdAt = 0, acceptedAt = 1))
        assertEquals("w-20261022-0008", store.nextId("20261022"))
        assertEquals("w-20261023-0001", store.nextId("20261023"))
        assertEquals(listOf("w-20261022-0007"), store.loadActive().map { it.warId })
        assertNotNull(store.load("w-20261022-0001"))
        assertEquals(mapOf("wars" to 2), store.wipe(dryRun = true))
        assertEquals(mapOf("wars" to 2), store.wipe(dryRun = false))
        assertFalse(storage.keys().any { it.startsWith("war.") })
    }
}
