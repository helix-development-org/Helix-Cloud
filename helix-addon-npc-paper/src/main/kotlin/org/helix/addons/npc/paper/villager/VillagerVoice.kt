package org.helix.addons.npc.paper.villager

/**
 * Sound event keys of the villager voices (design concept §16 "Villager-Stimme", stage 1 babble).
 *
 * Mirrors `de.tytoss.igui.sound.NetworkSounds.babble/hmm` (helix-gui), which this plugin does not depend on: the
 * events live in the shared `reich` sound pack shipped by Helix-GUIs. Keep both in sync.
 */
object VillagerVoice {
    /** Namespace of the network sound pack. */
    const val NAMESPACE = "reich"

    /** Voice used for ids without their own voice (the lobby narrator). */
    const val FALLBACK = "knoepfchen"

    /** Hearing range of villager voices and speech in blocks (§16 "Räumlich"). */
    const val RANGE = 12.0

    /**
     * @param villagerId villager id.
     * @return the babble syllable event, e.g. `reich:villager.mira.babble`.
     */
    fun babble(villagerId: String): String = "$NAMESPACE:villager.${voice(villagerId)}.babble"

    /**
     * @param villagerId villager id.
     * @return the "Hmm" event every line starts with, e.g. `reich:villager.mira.hmm`.
     */
    fun hmm(villagerId: String): String = "$NAMESPACE:villager.${voice(villagerId)}.hmm"

    private fun voice(villagerId: String): String = if (VillagerCast.get(villagerId) != null) villagerId else FALLBACK
}
