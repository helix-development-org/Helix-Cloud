import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: addon.json + addon.jar (the node-side NPC
 * persistence and `npc.*` actions) plus the Helix-NPC Paper component
 * (paper.jar), built from the sibling `helix-addon-npc-paper` module. The
 * Paper jar bundles the vendored INpc framework, so a Paper server needs
 * nothing else installed. No Velocity component and no resource pack — NPCs
 * are packet-based and skinned from Mojang.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages Helix-NPC as .hxa (addon.json + addon.jar + paper.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-npc-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(project(":helix-addon-npc-paper").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "paper.jar" }
    }
}
