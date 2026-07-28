package org.helix.node.gates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingOption
import org.helix.api.addon.ProfileSettingProvider
import org.helix.api.addon.ProfileSettingType

class ProfileSettingRegistryTest {
    private val registry = ProfileSettingRegistry()

    private val wings = ProfileSettingDescriptor(
        key = "wings",
        label = "Wings",
        type = ProfileSettingType.Choice(listOf(ProfileSettingOption("angel", "Angel Wings"))),
    )

    @Test
    fun `settingsFor aggregates every owner that contributes settings for a player`() {
        registry.register("cosmetics", settings { listOf(wings) })
        registry.register("subtitles", settings {
            listOf(ProfileSettingDescriptor("subtitle", "Subtitle", ProfileSettingType.FreeText()))
        })

        val result = registry.settingsFor("steve")

        assertEquals(listOf(wings), result["cosmetics"])
        assertEquals(1, result["subtitles"]!!.size)
    }

    @Test
    fun `an owner with no settings for a player is omitted`() {
        registry.register("cosmetics", settings { emptyList() })

        assertTrue(registry.settingsFor("steve").isEmpty())
    }

    @Test
    fun `unregistering an owner drops its providers`() {
        registry.register("cosmetics", settings { listOf(wings) })
        registry.unregisterOwner("cosmetics")

        assertTrue(registry.settingsFor("steve").isEmpty())
    }

    @Test
    fun `a throwing provider is skipped instead of failing the whole lookup`() {
        registry.register("broken", object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = error("boom")
        })
        registry.register("cosmetics", settings { listOf(wings) })

        assertEquals(mapOf("cosmetics" to listOf(wings)), registry.settingsFor("steve"))
    }

    @Test
    fun `notifyChanged calls onChanged only on providers registered under that owner`() {
        val received = mutableListOf<String>()
        registry.register("cosmetics", object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = listOf(wings)
            override fun onChanged(player: String, key: String, value: String) {
                received += "$player:$key:$value"
            }
        })
        registry.register("subtitles", settings { emptyList() })

        registry.notifyChanged("cosmetics", "steve", "wings", "angel")

        assertEquals(listOf("steve:wings:angel"), received)
    }

    @Test
    fun `notifyChanged for an unregistered owner is a no-op`() {
        registry.notifyChanged("ghost", "steve", "wings", "angel")
    }

    @Test
    fun `a throwing onChanged does not prevent the call from returning`() {
        registry.register("cosmetics", object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = emptyList()
            override fun onChanged(player: String, key: String, value: String) = error("boom")
        })

        registry.notifyChanged("cosmetics", "steve", "wings", "angel")
    }

    @Test
    fun `validate returns the first rejection reason from a provider registered under that owner`() {
        registry.register("nick", object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = emptyList()
            override fun validate(player: String, key: String, value: String): String? =
                if (value == "Admin") "that name is reserved" else null
        })

        assertEquals("that name is reserved", registry.validate("nick", "steve", "nick", "Admin"))
        assertEquals(null, registry.validate("nick", "steve", "nick", "Steve2"))
    }

    @Test
    fun `validate for an unregistered owner accepts everything`() {
        assertEquals(null, registry.validate("ghost", "steve", "wings", "angel"))
    }

    @Test
    fun `a throwing validator rejects instead of silently accepting`() {
        registry.register("nick", object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = emptyList()
            override fun validate(player: String, key: String, value: String): String? = error("boom")
        })

        assertTrue(registry.validate("nick", "steve", "nick", "Steve2") != null)
    }

    private fun settings(descriptors: (String) -> List<ProfileSettingDescriptor>) = object : ProfileSettingProvider {
        override fun settingsFor(player: String): List<ProfileSettingDescriptor> = descriptors(player)
    }
}
