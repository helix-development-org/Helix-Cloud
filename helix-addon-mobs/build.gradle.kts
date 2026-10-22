import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
    implementation(libs.kotlinx.serialization.json)
}

/**
 * Builds the mob-redesign resource pack from `src/pack`: the OptiFine/EMF
 * Custom Entity Models go to `assets/minecraft/optifine/cem/`, the restyled
 * vanilla mob textures to `assets/minecraft/textures/entity/` (same layout as
 * vanilla), plus `pack.mcmeta`. The node merges it into the network pack.
 */
val generatePack by tasks.registering(Zip::class) {
    group = "build"
    description = "Assembles the mob redesign resource pack (pack.zip)."
    archiveFileName.set("pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("pack"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    from(layout.projectDirectory.file("src/pack/pack.mcmeta"))
    from(layout.projectDirectory.dir("src/pack/resources/cem")) {
        include("**/*.jem")
        into("assets/minecraft/optifine/cem")
    }
    from(layout.projectDirectory.dir("src/pack/resources/textures/entity")) {
        include("**/*.png", "**/*.png.mcmeta")
        into("assets/minecraft/textures/entity")
    }
}

/**
 * Packages the addon as HXA: addon.json + addon.jar + pack.zip.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + pack.zip)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-mobs-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(generatePack)
}
