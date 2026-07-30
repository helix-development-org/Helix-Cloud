import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: addon.json + addon.jar plus the Helix-GUIs
 * Paper plugin (paper.jar) and its generated font resource pack (pack.zip),
 * both built by :helix-addon-guis-paper.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar + pack.zip)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-guis-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(project(":helix-addon-guis-paper").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "paper.jar" }
    }
    from(project(":helix-addon-guis-paper").tasks.named("generatePack")) {
        rename { "pack.zip" }
    }
}
