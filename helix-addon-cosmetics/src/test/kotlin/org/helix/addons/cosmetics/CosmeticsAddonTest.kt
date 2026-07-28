package org.helix.addons.cosmetics

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.addon.ProfileSettingType
import org.helix.api.player.OnlinePlayer

class CosmeticsAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("cosmetics"))
    private val storedValues = mutableMapOf<String, String>()

    private fun enable() {
        context.registerAction(ActionDescriptor("profile.setting.get", "d", "u")) { invocation ->
            val (player, _, key) = invocation.arguments
            ActionResult.ok(storedValues["$player:$key"] ?: "none")
        }
        CosmeticsAddon().also { it.onEnable(context) }
    }

    @Test
    fun `settingsFor exposes wings and headwear with none plus the full catalog`() {
        enable()

        val descriptors = context.profileSettingProviders.single().settingsFor("steve")

        assertEquals(setOf("wings", "headwear"), descriptors.map { it.key }.toSet())
        val wings = descriptors.single { it.key == "wings" }.type as ProfileSettingType.Choice
        assertEquals(listOf("none") + CosmeticCatalog.wings.map { it.id }, wings.options.map { it.id })
    }

    @Test
    fun `a rank-gated cosmetic is locked without the permission and unlocked with it`() {
        enable()

        val gated = CosmeticCatalog.wings.first { it.permission.isNotBlank() }
        val wingsBefore = context.profileSettingProviders.single().settingsFor("steve")
            .single { it.key == "wings" }.type as ProfileSettingType.Choice
        assertFalse(wingsBefore.options.single { it.id == gated.id }.unlocked)

        context.permissionCheck = { _, permission -> permission == gated.permission }
        val wingsAfter = context.profileSettingProviders.single().settingsFor("steve")
            .single { it.key == "wings" }.type as ProfileSettingType.Choice
        assertTrue(wingsAfter.options.single { it.id == gated.id }.unlocked)
    }

    @Test
    fun `choosing a cosmetic publishes its CustomModelData as a bridge value`() {
        enable()
        val angel = CosmeticCatalog.wings.single { it.id == "angel" }
        storedValues["steve:wings"] = "angel"

        context.profileSettingProviders.single().onChanged("steve", "wings", "angel")

        assertEquals(angel.customModelData.toString(), context.bridgeValues["cosmetic.wings.steve"])
    }

    @Test
    fun `clearing back to none unpublishes the bridge value`() {
        enable()
        storedValues["steve:wings"] = "angel"
        context.profileSettingProviders.single().onChanged("steve", "wings", "angel")
        assertTrue(context.bridgeValues.containsKey("cosmetic.wings.steve"))

        storedValues["steve:wings"] = "none"
        context.profileSettingProviders.single().onChanged("steve", "wings", "none")

        assertFalse(context.bridgeValues.containsKey("cosmetic.wings.steve"))
    }

    @Test
    fun `wings and headwear are independent bridge values`() {
        enable()
        storedValues["steve:wings"] = "angel"
        storedValues["steve:headwear"] = "crown_silver"

        context.profileSettingProviders.single().onChanged("steve", "wings", "angel")
        context.profileSettingProviders.single().onChanged("steve", "headwear", "crown_silver")

        val angel = CosmeticCatalog.wings.single { it.id == "angel" }
        val crown = CosmeticCatalog.headwear.single { it.id == "crown_silver" }
        assertEquals(angel.customModelData.toString(), context.bridgeValues["cosmetic.wings.steve"])
        assertEquals(crown.customModelData.toString(), context.bridgeValues["cosmetic.headwear.steve"])
    }

    @Test
    fun `joining republishes existing choices, leaving unpublishes both`() {
        enable()
        storedValues["steve:wings"] = "angel"
        storedValues["steve:headwear"] = "crown_silver"

        context.playerListeners.single().onJoin(OnlinePlayer(name = "steve"))
        assertTrue(context.bridgeValues.containsKey("cosmetic.wings.steve"))
        assertTrue(context.bridgeValues.containsKey("cosmetic.headwear.steve"))

        context.playerListeners.single().onLeave(OnlinePlayer(name = "steve"))
        assertNull(context.bridgeValues["cosmetic.wings.steve"])
        assertNull(context.bridgeValues["cosmetic.headwear.steve"])
    }
}
