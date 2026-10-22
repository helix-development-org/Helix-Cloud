package org.helix.addons.permissions

import org.helix.api.design.PlayerRanks
import org.helix.api.design.RankBadges
import org.helix.api.design.StaffRanks
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankBadgeUpgradeTest {
    private val storage = InMemoryAddonStorage()
    private val store = PermissionStore(InMemoryAddonStorage())
    private val upgrade = RankBadgeUpgrade(store, storage)
    private val owner = StaffRanks.ALL.first()
    private val player = StaffRanks.ALL.last()

    @Test
    fun `old standard prefixes become badges, the default group loses its tag`() {
        store.saveGroup(PermissionGroup("owner", 100, prefix = owner.legacyPrefix, color = owner.colorTag))
        store.saveGroup(PermissionGroup("default", 0, default = true, prefix = player.legacyPrefix, color = player.colorTag))
        store.saveGroup(PermissionGroup("tester", 10, prefix = PlayerRanks.TESTER.legacyPrefix))
        store.saveGroup(PermissionGroup("betatester", 1, prefix = PlayerRanks.BETA.legacyPrefix))

        val changed = upgrade.upgradeOnce()

        assertEquals(setOf("owner", "default", "tester", "betatester"), changed.toSet())
        assertEquals(RankBadges.OWNER.prefix, store.group("owner")!!.prefix)
        assertEquals("", store.group("default")!!.prefix)
        assertEquals(player.colorTag, store.group("default")!!.color)
        assertEquals(RankBadges.TESTER.prefix, store.group("tester")!!.prefix)
        assertEquals(RankBadges.BETA.prefix, store.group("betatester")!!.prefix)
    }

    @Test
    fun `team-edited prefixes stay unless forced, and the upgrade runs only once`() {
        store.saveGroup(PermissionGroup("owner", 100, prefix = "&4Chef "))
        assertTrue(upgrade.upgradeOnce().isEmpty())
        assertEquals("&4Chef ", store.group("owner")!!.prefix)

        store.saveGroup(PermissionGroup("mod", 70, prefix = StaffRanks.ALL.first { it.tag == "[Mod]" }.legacyPrefix))
        assertTrue(upgrade.upgradeOnce().isEmpty(), "second run is a no-op")

        assertEquals(listOf("owner", "mod").toSet(), upgrade.apply(force = true).toSet())
        assertEquals(RankBadges.OWNER.prefix, store.group("owner")!!.prefix)
    }

    @Test
    fun `plain text replaces badge glyphs for places without the resource pack`() {
        assertEquals("[Owner] Tytoss", RankBadges.plain(RankBadges.OWNER.prefix + "Tytoss"))
        assertEquals("<#ff0000>x</#ff0000>", RankBadges.plain("<#ff0000>x</#ff0000>"))
        assertEquals(RankBadges.MOD, RankBadges.of("[Mod]"))
    }
}
