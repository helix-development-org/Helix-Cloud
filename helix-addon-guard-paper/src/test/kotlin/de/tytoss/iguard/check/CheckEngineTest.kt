package de.tytoss.iguard.check

import de.tytoss.iguard.EngineHarness
import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.TARGET
import de.tytoss.iguard.WORLD
import de.tytoss.iguard.api.IGuardExemptionScope
import de.tytoss.iguard.environment
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockAction
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.DigSample
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.ServerTeleportFrame
import de.tytoss.iguard.model.ServerVelocityFrame
import de.tytoss.iguard.model.SwingFrame
import de.tytoss.iguard.model.TeleportConfirmFrame
import de.tytoss.iguard.model.Vec3
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckEngineTest {
    // --- fast-ladder / high-jump on climbables (fix: climbable used to exempt them) ---

    @Test
    fun `fast ladder is detected on a climbable`() {
        val harness = EngineHarness().apply { tags = setOf("climbable") }
        var position = harness.position
        harness.tick(position, onGround = true)
        repeat(40) {
            position += Vec3(0.0, 0.3, 0.0)
            harness.tick(position, onGround = false)
        }

        assertTrue(harness.failed("movement.fastladder.a"), "0.3 blocks/tick on a ladder must flag")
        assertTrue(harness.vl("movement.fastladder.a") > 0.0)
    }

    @Test
    fun `vanilla climbing and jumping onto a ladder stay clean`() {
        val harness = EngineHarness().apply { tags = setOf("climbable") }
        var position = harness.position
        harness.tick(position, onGround = true)
        // jump takeoff at the ladder, then the vanilla climb speed
        position += Vec3(0.0, 0.42, 0.0)
        harness.tick(position, onGround = false)
        repeat(40) {
            position += Vec3(0.0, 0.1176, 0.0)
            harness.tick(position, onGround = false)
        }

        assertTrue(harness.results("movement.fastladder.a").isNotEmpty(), "fast-ladder must be evaluated on climbables")
        assertFalse(harness.failed("movement.fastladder.a"))
        assertFalse(harness.failed("movement.highjump.a"))
    }

    @Test
    fun `high jump is evaluated on a climbable too`() {
        val harness = EngineHarness().apply { tags = setOf("climbable") }
        harness.tick(harness.position, onGround = true)
        harness.tick(harness.position, onGround = true)
        harness.tick(harness.position + Vec3(0.0, 1.1, 0.0), onGround = false)

        assertTrue(harness.failed("movement.highjump.a"))
    }

    @Test
    fun `climbable plus another exempting tag stays exempt`() {
        val harness = EngineHarness().apply { tags = setOf("climbable", "levitation") }
        var position = harness.position
        harness.tick(position, onGround = true)
        repeat(20) {
            position += Vec3(0.0, 0.3, 0.0)
            harness.tick(position, onGround = false)
        }

        assertTrue(harness.results("movement.fastladder.a").isEmpty())
    }

    // --- low TPS is context, not an exemption ---

    @Test
    fun `phase is still checked under low tps`() {
        val harness = EngineHarness().apply {
            tags = setOf("low-tps")
            boxes = { listOf(Box(-2.0, 63.0, -2.0, 3.0, 64.0, 3.0), Box(-2.0, 64.0, 1.0, 3.0, 66.0, 2.0)) }
        }
        harness.tick(Vec3(0.5, 64.0, 0.5), onGround = true)
        harness.tick(Vec3(0.5, 64.0, 0.5), onGround = true)
        harness.tick(Vec3(0.5, 64.0, 1.1), onGround = true)

        assertTrue(harness.failed("movement.phase.a"), "walking into a wall must flag although the server lags")
        assertTrue(harness.reports.none { it.frameType == "movement" && it.exemptions.any { reason -> reason.contains("low-tps") } })
    }

    // --- setback / teleport window (fix: setbacks bought a full teleport exemption) ---

    @Test
    fun `teleport exemption ends shortly after the confirm`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        harness.send(teleport(harness, id = 5))

        assertTrue(harness.exemptions.isExempt(PLAYER, harness.now + 100))
        harness.now += 100
        harness.send(TeleportConfirmFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, 5))

        assertTrue(harness.exemptions.isExempt(PLAYER, harness.now + 200), "grace for in-flight packets")
        assertFalse(harness.exemptions.isExempt(PLAYER, harness.now + 300), "no 1.5 s window after the confirm")
    }

    @Test
    fun `an unconfirmed setback teleport is capped at one second`() {
        val harness = EngineHarness().apply { recentSetback = true }
        harness.tick(harness.position, onGround = true)
        harness.send(teleport(harness, id = 9))

        assertTrue(harness.exemptions.isExempt(PLAYER, harness.now + 900))
        assertFalse(harness.exemptions.isExempt(PLAYER, harness.now + 1_100))
        assertEquals(listOf("movement:setback-teleport"), harness.exemptions.activeReasons(PLAYER, harness.now + 10))
    }

    @Test
    fun `a teleport never suspends combat or world checks`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        harness.send(teleport(harness, id = 3))
        harness.move(harness.position, onGround = true)

        assertFalse(harness.exemptions.isFullyExempt(PLAYER, harness.now + 10))
        digFarAway(harness)
        assertTrue(harness.failed("world.interactionreach.a"))
    }

    // --- flight / vehicles only exempt movement (fix: they switched off combat and world checks) ---

    @Test
    fun `flying players are still judged for interaction reach`() {
        val harness = EngineHarness().apply { tags = setOf("flight") }
        harness.tick(harness.position, onGround = true)
        digFarAway(harness)

        assertTrue(harness.failed("world.interactionreach.a"))
    }

    @Test
    fun `knockback exemption keeps world checks running`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        harness.send(ServerVelocityFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, Vec3(0.4, 0.4, 0.0), "velocity"))
        assertTrue(harness.exemptions.isExempt(PLAYER, harness.now))
        digFarAway(harness)

        assertTrue(harness.failed("world.interactionreach.a"))
    }

    @Test
    fun `creative mode and full exemptions skip world checks`() {
        val creative = EngineHarness().apply { tags = setOf("game-mode") }
        creative.tick(creative.position, onGround = true)
        digFarAway(creative)
        assertTrue(creative.results("world.interactionreach.a").isEmpty())
        assertTrue(creative.reports.last().exemptions.contains("tag:game-mode"))

        val exempt = EngineHarness()
        exempt.tick(exempt.position, onGround = true)
        exempt.exemptions.exempt(PLAYER, Duration.ofSeconds(5), "cutscene", IGuardExemptionScope.ALL)
        digFarAway(exempt)
        assertTrue(exempt.results("world.interactionreach.a").isEmpty())
    }

    @Test
    fun `seated players act from their seat position`() {
        val harness = EngineHarness()
        harness.tick(Vec3(0.5, 64.0, 0.5), onGround = true)
        // now riding: no more position packets, the server sees the player 20 blocks further
        harness.tags = setOf("vehicle")
        harness.sample(at = Vec3(20.5, 64.0, 0.5))
        harness.send(
            BlockActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.START_DIG, 21, 64, 0, "UP", harness.nextInteraction()),
        )

        assertTrue(harness.results("world.interactionreach.a").isNotEmpty(), "seated players are judged")
        assertFalse(harness.failed("world.interactionreach.a"), "reach is measured from the seat")
    }

    // --- timer fallback for clients without the tick-end packet ---

    @Test
    fun `timer detects a fast legacy client through movement packets`() {
        val harness = EngineHarness().apply { clientVersion = "V_1_20_5" }
        repeat(300) {
            harness.move(harness.position, onGround = true, positionChanged = false)
            harness.now += 40
        }

        val failure = harness.results("movement.timer.a").firstOrNull { it.failed && it.counted }
        assertNotNull(failure, "25 packets/s for 12 s must flag")
        assertEquals("movement", failure.evidence["source"])
    }

    @Test
    fun `timer passes a legacy client at 20 packets per second and decays`() {
        val harness = EngineHarness().apply { clientVersion = "V_1_20_5" }
        repeat(300) {
            harness.move(harness.position, onGround = true, positionChanged = false)
            harness.now += 50
        }

        assertFalse(harness.failed("movement.timer.a"))
        assertTrue(harness.results("movement.timer.a").isNotEmpty(), "the timer is evaluated (and decays) on every tick")
    }

    @Test
    fun `explicit tick packets replace the movement fallback`() {
        val harness = EngineHarness()
        repeat(260) {
            harness.move(harness.position, onGround = true, positionChanged = false)
            harness.tickEnd()
            harness.now += 50
        }

        assertFalse(harness.failed("movement.timer.a"), "movement + tick-end per tick must not count twice")
    }

    // --- incidents: one write per change, sub-threshold noise opens none ---

    @Test
    fun `sub-threshold failures never open an incident`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        digFarAway(harness)

        assertEquals(1.0, harness.vl("world.interactionreach.a"))
        assertNull(harness.engine.incidentSnapshot(PLAYER), "VL 1 of alert-vl 3 is noise")
        assertTrue(harness.store.incidents.isEmpty())
        assertNull(harness.store.violations.single().incidentId)
    }

    @Test
    fun `an incident is written once per change, not once per failure`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        repeat(30) {
            digFarAway(harness)
            harness.now += 600
            harness.tick(harness.position, onGround = true)
        }

        val failures = harness.store.violations.count { it.checkId == "world.interactionreach.a" }
        assertTrue(failures >= 20, "violations are still written per counted failure ($failures)")
        val writes = harness.store.incidents.size
        assertTrue(writes in 1..failures / 2, "incident upserts are throttled ($writes for $failures failures)")
        assertTrue(harness.store.violations.none { it.incidentId == null && it.violationLevel >= 3.0 })
        assertEquals(WORLD, harness.store.incidents.last().worldId, "world comes from the snapshot, not Bukkit")
    }

    // --- time-based decay ---

    @Test
    fun `violation levels decay per second, not per packet`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        digFarAway(harness)
        assertEquals(1.0, harness.vl("world.interactionreach.a"))

        // twenty passing evaluations within 100 ms only decay 100 ms worth (2.0/s)
        repeat(20) {
            harness.now += 5
            digNearby(harness)
        }
        assertEquals(0.8, harness.vl("world.interactionreach.a"), 1e-9)

        harness.now += 250
        digNearby(harness)
        assertEquals(0.3, harness.vl("world.interactionreach.a"), 1e-9)
    }

    @Test
    fun `continuous failing does not decay between failures`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        digFarAway(harness)
        harness.now += 100
        digFarAway(harness) // held back by the 500 ms signal cooldown
        harness.now += 500
        digFarAway(harness)

        assertEquals(2.0, harness.vl("world.interactionreach.a"), 1e-9)
    }

    // --- combat ---

    @Test
    fun `reach beyond the profile limit flags, a normal hit does not`() {
        val far = EngineHarness()
        far.tick(far.position, onGround = true)
        placeTarget(far, distance = 3.7)
        far.send(AttackFrame(PLAYER, far.nextSequence(), far.now, far.clientVersion, 42))
        assertTrue(far.failed("combat.reach.a"))
        assertTrue(far.reports.last().results.any { it.checkId == "combat.rotation.a" })

        val near = EngineHarness()
        near.tick(near.position, onGround = true)
        placeTarget(near, distance = 2.4)
        near.send(AttackFrame(PLAYER, near.nextSequence(), near.now, near.clientVersion, 42))
        assertFalse(near.failed("combat.reach.a"))
        assertFalse(near.failed("combat.rotation.a"), "facing the target (yaw 0 = +z) is a rotation hit")
    }

    @Test
    fun `perfectly regular fast clicking flags the autoclicker`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        repeat(45) {
            harness.send(AttackFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, 99))
            harness.send(SwingFrame(PLAYER, harness.nextSequence(), harness.now + 1, harness.clientVersion))
            harness.now += 60
        }

        assertTrue(harness.failed("combat.autoclicker.a"))
        assertFalse(harness.failed("combat.noswing.a"))
    }

    @Test
    fun `a hit from beyond reach plus one is still measured`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        placeTarget(harness, distance = 4.6)
        harness.send(AttackFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, 42))

        assertTrue(harness.failed("combat.reach.a"), "the view ray stops at reach + 1, the closest-point distance takes over")
    }

    @Test
    fun `two different mobs in one client tick flag multi-target, consecutive ticks do not`() {
        val aura = EngineHarness()
        aura.tick(aura.position, onGround = true)
        attackMob(aura, 900)
        attackMob(aura, 901)
        assertTrue(aura.failed("combat.multitarget.a"))

        val legit = EngineHarness()
        legit.tick(legit.position, onGround = true)
        repeat(6) { index ->
            attackMob(legit, 900 + index % 2)
            legit.tick(legit.position, onGround = true)
        }
        assertFalse(legit.failed("combat.multitarget.a"))
        assertTrue(legit.results("combat.multitarget.a").isNotEmpty(), "evaluated for mob targets")
    }

    @Test
    fun `without tick-end packets standing still does not merge two mob hits into one tick`() {
        val harness = EngineHarness()
        harness.move(harness.position, onGround = true)
        attackMob(harness, 900)
        harness.now += 400
        attackMob(harness, 901)

        assertFalse(harness.failed("combat.multitarget.a"))
    }

    @Test
    fun `machine-regular clicking flags from ten cps, tick-raster twelve cps does not`() {
        val ten = EngineHarness()
        ten.tick(ten.position, onGround = true)
        clickMob(ten, List(36) { 100L })
        assertTrue(ten.failed("combat.autoclicker.a"))

        val twelve = EngineHarness()
        twelve.tick(twelve.position, onGround = true)
        clickMob(twelve, List(42) { listOf(100L, 50L, 100L)[it % 3] })
        assertFalse(twelve.failed("combat.autoclicker.a"), "50/100ms alternation has a coefficient of variation of ~0.28")

        val strict = EngineHarness(tweak = { it.copy(tuning = it.tuning.copy(autoclickerMinCps = 13.0)) })
        strict.tick(strict.position, onGround = true)
        clickMob(strict, List(36) { 100L })
        assertFalse(strict.failed("combat.autoclicker.a"), "min-cps is configurable")
    }

    @Test
    fun `attacks without arm swings flag no-swing`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        repeat(6) {
            harness.send(AttackFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, 99))
            harness.now += 300
        }

        assertTrue(harness.failed("combat.noswing.a"))
    }

    @Test
    fun `flying players are still judged for combat`() {
        val harness = EngineHarness().apply { tags = setOf("flight") }
        harness.tick(harness.position, onGround = true)
        repeat(6) {
            harness.send(AttackFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, 99))
            harness.now += 300
        }

        assertTrue(harness.failed("combat.noswing.a"))
    }

    // --- world / inventory ---

    @Test
    fun `starting many digs per second flags nuker`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        repeat(14) { index ->
            harness.send(
                BlockActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.START_DIG, index % 3 - 1, 64, 2, "UP", harness.nextInteraction()),
            )
            harness.now += 60
        }

        assertTrue(harness.failed("world.nuker.a"))
    }

    @Test
    fun `fastbreak compares client ticks with the measured vanilla break time`() {
        val stone = DigSample(8.0 / 1.5 / 30.0, 4, "minecraft:stone")
        val legit = EngineHarness().apply { pitch = 30f }
        legit.tick(legit.position, onGround = true)
        mine(legit, stone, ticks = 5)
        assertFalse(legit.failed("world.fastbreak.a"))
        assertTrue(legit.results("world.fastbreak.a").isNotEmpty())

        val cheat = EngineHarness().apply { pitch = 30f }
        cheat.tick(cheat.position, onGround = true)
        mine(cheat, stone, ticks = 1)
        assertTrue(cheat.failed("world.fastbreak.a"))
        val evidence = cheat.results("world.fastbreak.a").first { it.failed }.evidence
        assertEquals(5, evidence["minimumTicks"])
        assertEquals("tick-end", evidence["source"])
    }

    @Test
    fun `fastbreak spares instant blocks, creative players and unmeasured slow digs`() {
        val instant = EngineHarness().apply { pitch = 30f }
        instant.tick(instant.position, onGround = true)
        mine(instant, DigSample(1.4, 1, "minecraft:short_grass"), ticks = 0)
        assertFalse(instant.failed("world.fastbreak.a"))

        val creative = EngineHarness().apply { pitch = 30f; tags = setOf("game-mode") }
        creative.tick(creative.position, onGround = true)
        mine(creative, DigSample(0.05, 3, "minecraft:obsidian"), ticks = 0)
        assertTrue(creative.results("world.fastbreak.a").isEmpty())

        val unmeasured = EngineHarness().apply { pitch = 30f }
        unmeasured.tick(unmeasured.position, onGround = true)
        mine(unmeasured, null, ticks = 1)
        assertFalse(unmeasured.failed("world.fastbreak.a"), "50ms without a measurement is not judged")
        mine(unmeasured, null, ticks = 0)
        assertTrue(unmeasured.failed("world.fastbreak.a"), "a FINISH in the START flush still is")
    }

    @Test
    fun `fastbreak times legacy clients in milliseconds with a ping allowance`() {
        val stone = DigSample(8.0 / 1.5 / 30.0, 4, "minecraft:stone")
        val fast = EngineHarness().apply { pitch = 30f }
        fast.move(fast.position, onGround = true)
        mineLegacy(fast, stone, millis = 100)
        assertTrue(fast.failed("world.fastbreak.a"))
        assertEquals("millis", fast.results("world.fastbreak.a").first { it.failed }.evidence["source"])

        val slow = EngineHarness().apply { pitch = 30f }
        slow.move(slow.position, onGround = true)
        mineLegacy(slow, stone, millis = 130)
        assertFalse(slow.failed("world.fastbreak.a"), "130ms + 60ms tolerance reaches 70% of 250ms")
    }

    @Test
    fun `impossible inventory clicks are deterministic evidence`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        harness.send(InventoryClickFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, 200, 1, 0, 0, "PICKUP"))

        assertTrue(harness.failed("inventory.impossible.a"))
        val incident = harness.store.incidents.single()
        assertTrue(incident.confidence >= 0.85, "deterministic evidence reaches the proof tier")
        assertEquals("WOULD_BAN_24H", incident.shadowAction, "shadow mode only records the decision")
    }

    @Test
    fun `reports list every evaluated check with its verdict`() {
        val harness = EngineHarness()
        harness.tick(harness.position, onGround = true)
        harness.tick(harness.position + Vec3(0.0, 0.0, 0.1), onGround = true)
        val movement = harness.reports.last { it.frameType == "movement" }

        assertFalse(movement.exempt)
        assertTrue(movement.results.map { it.checkId }.containsAll(listOf("movement.speed.a", "movement.fly.a", "movement.highjump.a")))
        assertTrue(movement.results.none { it.failed })
    }

    private fun teleport(harness: EngineHarness, id: Int) = ServerTeleportFrame(
        PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, id, Vec3(10.5, 64.0, 10.5), Vec3(0.0, 0.0, 0.0),
        0f, 0f, false, false, false, false, false,
    )

    // A dig 10 blocks away (interaction reach limit 6.5).
    private fun digFarAway(harness: EngineHarness) = harness.send(
        BlockActionFrame(
            PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.START_DIG,
            harness.position.x.toInt(), 64, harness.position.z.toInt() + 10, "UP", harness.nextInteraction(),
        ),
    )

    // A dig right in front of the player (yaw 0 looks at +z).
    private fun digNearby(harness: EngineHarness) = harness.send(
        BlockActionFrame(
            PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.CANCEL_DIG,
            harness.position.x.toInt(), 65, harness.position.z.toInt() + 2, "UP", harness.nextInteraction(),
        ),
    )

    private fun attackMob(harness: EngineHarness, entityId: Int) {
        harness.send(AttackFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, entityId))
        harness.send(SwingFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion))
    }

    // Attacks a mob after each of the given intervals (ms), with arm swings.
    private fun clickMob(harness: EngineHarness, intervals: List<Long>) = intervals.forEach { interval ->
        attackMob(harness, 99)
        harness.now += interval
    }

    // START on the block in front, [ticks] client ticks, FINISH carrying [sample].
    private fun mine(harness: EngineHarness, sample: DigSample?, ticks: Int) {
        val z = harness.position.z.toInt() + 2
        harness.send(BlockActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.START_DIG, 0, 64, z, "NORTH", harness.nextInteraction()))
        repeat(ticks) { harness.tick(harness.position, onGround = true) }
        harness.send(BlockActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.FINISH_DIG, 0, 64, z, "NORTH", harness.nextInteraction(), dig = sample))
        repeat(5) { harness.tick(harness.position, onGround = true) }
    }

    // START, [millis] later FINISH carrying [sample] – no tick-end packets.
    private fun mineLegacy(harness: EngineHarness, sample: DigSample, millis: Long) {
        val z = harness.position.z.toInt() + 2
        harness.send(BlockActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.START_DIG, 0, 64, z, "NORTH", harness.nextInteraction()))
        harness.now += millis
        harness.send(BlockActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, BlockAction.FINISH_DIG, 0, 64, z, "NORTH", harness.nextInteraction(), dig = sample))
    }

    // A target player [distance] blocks in front (+z) of the attacker's eyes.
    private fun placeTarget(harness: EngineHarness, distance: Double) {
        val origin = harness.position
        val targetPosition = Vec3(origin.x, origin.y, origin.z + distance + 0.3)
        listOf(harness.now - 150, harness.now - 50, harness.now).forEach { at ->
            harness.snapshots.update(TARGET, "Target", 42, environment(at, targetPosition))
        }
        // keep the attacker's own sample fresh
        harness.sample()
        harness.send(MovementFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, false, true, origin, 0f, 0f, true))
    }
}
