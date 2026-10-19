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
 * Packages the addon as HXA: addon.json + addon.jar plus the Paper component as `paper.jar`. The Paper jar is
 * built by `:helix-addon-feedback-paper` when that module is part of the build (like lobby/lobby-paper);
 * otherwise a prebuilt `libs/paper.jar` (and optional `libs/pack.zip`) is taken, and without either the HXA
 * ships the node side alone.
 */
val paperModule = rootProject.findProject(":helix-addon-feedback-paper")

val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-feedback-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    if (paperModule != null) {
        from(paperModule.tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
            rename { "paper.jar" }
        }
        from(layout.projectDirectory.dir("libs")) {
            include("pack.zip")
        }
    } else {
        from(layout.projectDirectory.dir("libs")) {
            include("paper.jar", "pack.zip")
        }
    }
}
