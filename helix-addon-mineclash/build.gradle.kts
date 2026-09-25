import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
    // Provided by the node classloader at runtime (helix-node bundles both); only needed to compile.
    compileOnly(libs.hikaricp)
    compileOnly(libs.postgresql)
}

/**
 * Packages the addon as HXA: addon.json + addon.jar plus the Paper game plugin
 * (`libs/paper.jar`) and the card/HUD resource pack (`libs/pack.zip`). Both are
 * built by the MineClash repository (`./gradlew deployHelix`) and dropped into
 * `libs/`; when they are missing the HXA still packages the node side alone.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar + pack.zip)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-mineclash-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(layout.projectDirectory.dir("libs")) {
        include("paper.jar", "pack.zip")
    }
}
