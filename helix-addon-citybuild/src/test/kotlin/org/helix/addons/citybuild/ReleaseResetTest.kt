package org.helix.addons.citybuild

import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.release.ReleaseReset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseResetTest {
    private val context = RecordingAddonContext(createTempDirectory("citybuild-release"))

    init {
        CityBuildAddon(gatewayFactory = { FakeKronen() }).onEnable(context)
    }

    @Test
    fun `release reset wipes gameplay data but keeps warps and configuration`() {
        val home = context.run("citybuild.home.set", ALICE, "CityBuild-1", "3", """{"name":"base","world":"plots","x":1,"y":64,"z":1,"yaw":0,"pitch":0}""")
        assertTrue(home.success, home.lines.joinToString())
        assertTrue(context.run("citybuild.warp.set", "CityBuild-1", """{"name":"Markt","icon":"EMERALD","description":"","world":"spawn","x":0,"y":64,"z":0,"yaw":0,"pitch":0,"sortOrder":1}""").success)
        assertTrue(context.run("citybuild.config.set", "plots.mergePrice", "3000").success)

        val plan = ReleaseReset.parse(context.run("citybuild.release.reset", "plan").lines.single())!!
        assertEquals(1, plan.deleted["homes"])
        assertTrue(context.run("citybuild.home.list", ALICE, "CityBuild-1").lines.single().contains("base"), "a plan changes nothing")

        val run = ReleaseReset.parse(context.run("citybuild.release.reset", "run").lines.single())!!
        assertEquals(ReleaseReset.RUN, run.mode)
        assertFalse(context.run("citybuild.home.list", ALICE, "CityBuild-1").lines.single().contains("base"))
        assertTrue(context.run("citybuild.warp.list", "CityBuild-1").lines.single().contains("Markt"), "warps are kept")
        assertTrue(context.run("citybuild.config.bundle").lines.single().contains("3000"), "configuration is kept")
        assertFalse(context.handlers.getValue("citybuild.release.reset").first.bridgeInvocable)
        assertEquals(ReleaseReset.PERMISSION, context.handlers.getValue("citybuild.release.reset").first.permission)
        assertEquals("usage", context.run("citybuild.release.reset", "maybe").lines.first())
    }
}
