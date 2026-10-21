package de.tytoss.iguard.api

import de.tytoss.iguard.PLAYER
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExemptionManagerTest {
    private var now = 10_000L
    private val manager = ExemptionManager { now }

    @Test
    fun `movement scope suspends movement checks only`() {
        manager.exempt(PLAYER, Duration.ofMillis(500), "velocity", IGuardExemptionScope.MOVEMENT)

        assertTrue(manager.isExempt(PLAYER))
        assertFalse(manager.isFullyExempt(PLAYER))
        assertEquals(listOf("movement:velocity"), manager.activeReasons(PLAYER))
    }

    @Test
    fun `the plain overload exempts everything`() {
        manager.exempt(PLAYER, Duration.ofMillis(500), "cutscene")

        assertTrue(manager.isExempt(PLAYER))
        assertTrue(manager.isFullyExempt(PLAYER))
    }

    @Test
    fun `scopes are independent and the longest grant wins within one`() {
        manager.exempt(PLAYER, Duration.ofMillis(1_000), "long", IGuardExemptionScope.MOVEMENT)
        manager.exempt(PLAYER, Duration.ofMillis(100), "short", IGuardExemptionScope.MOVEMENT)
        manager.exempt(PLAYER, Duration.ofMillis(200), "panel", IGuardExemptionScope.ALL)

        now += 150
        assertEquals(listOf("all:panel", "movement:long"), manager.activeReasons(PLAYER))
        now += 100
        assertFalse(manager.isFullyExempt(PLAYER))
        assertTrue(manager.isExempt(PLAYER))
        now += 800
        assertFalse(manager.isExempt(PLAYER))
    }

    @Test
    fun `explicit timestamps and cancel`() {
        manager.exempt(PLAYER, Duration.ofMillis(300), "teleport", IGuardExemptionScope.MOVEMENT, now = 50_000L, external = false)
        assertTrue(manager.isExempt(PLAYER, 50_299L))
        assertFalse(manager.isExempt(PLAYER, 50_300L))

        val handle = manager.exempt(PLAYER, Duration.ofMillis(300), "teleport", IGuardExemptionScope.MOVEMENT, now = 60_000L, external = false)
        assertEquals(IGuardExemptionScope.MOVEMENT, handle.scope)
        assertTrue(handle.cancel())
        assertFalse(handle.cancel())
        assertFalse(manager.isExempt(PLAYER, 60_100L))
    }

    @Test
    fun `cancelling a replaced grant keeps the longer one`() {
        val short = manager.exempt(PLAYER, Duration.ofMillis(100), "short", IGuardExemptionScope.MOVEMENT)
        manager.exempt(PLAYER, Duration.ofMillis(1_000), "long", IGuardExemptionScope.MOVEMENT)

        assertFalse(short.cancel())
        assertTrue(manager.isExempt(PLAYER))
    }

    @Test
    fun `only external grants reach the listener`() {
        val grants = mutableListOf<ExemptionGrant>()
        manager.grantListener = { grants += it }
        manager.exempt(PLAYER, Duration.ofMillis(100), "api")
        manager.exempt(PLAYER, Duration.ofMillis(100), "engine", IGuardExemptionScope.MOVEMENT, external = false)

        assertEquals(listOf(ExemptionGrant(PLAYER, now, 100, "api", IGuardExemptionScope.ALL)), grants)
    }

    @Test
    fun `remove clears both scopes`() {
        manager.exempt(PLAYER, Duration.ofSeconds(1), "a")
        manager.exempt(PLAYER, Duration.ofSeconds(1), "b", IGuardExemptionScope.MOVEMENT)
        manager.remove(PLAYER)

        assertFalse(manager.isExempt(PLAYER))
        assertEquals(null, manager.expiresAt(PLAYER))
    }
}
