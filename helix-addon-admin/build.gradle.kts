import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: addon.json + addon.jar plus the Paper-side component (paper.jar), built by
 * :helix-addon-admin-paper. The client side is the separate Fabric mod in `helix-admin-mod/`.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-admin-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(project(":helix-addon-admin-paper").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "paper.jar" }
    }
}
