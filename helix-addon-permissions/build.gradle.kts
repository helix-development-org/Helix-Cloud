import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
    implementation("org.yaml:snakeyaml:2.3")
}

// snakeyaml is bundled into the addon jar (plugin.yml catalog scanning); the
// node only provides helix-api, the sdk and the kotlin runtime.
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.name.startsWith("snakeyaml") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

/**
 * Packages the addon as HXA: a zip with addon.json and addon.jar.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-permissions-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
}
