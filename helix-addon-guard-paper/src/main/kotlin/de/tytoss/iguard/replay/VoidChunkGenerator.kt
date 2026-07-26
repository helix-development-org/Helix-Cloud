package de.tytoss.iguard.replay

import org.bukkit.generator.ChunkGenerator

/** Empty/void world generator for the isolated replay world (terrain is pasted in via Rune). */
class VoidChunkGenerator : ChunkGenerator() {
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false
}
