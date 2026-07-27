import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: a zip with addon.json and addon.jar.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-nick-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
}
