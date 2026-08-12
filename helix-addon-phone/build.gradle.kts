import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: addon.json + addon.jar. The Paper-side phone
 * component (paper.jar) and its generated resource pack (pack.zip) are added
 * once :helix-addon-phone-paper exists.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar + pack.zip)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-phone-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(project(":helix-addon-phone-paper").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "paper.jar" }
    }
    from(project(":helix-addon-phone-paper").tasks.named("generatePack")) {
        rename { "pack.zip" }
    }
}
