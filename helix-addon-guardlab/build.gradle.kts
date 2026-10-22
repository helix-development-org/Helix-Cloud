import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: addon.json + addon.jar plus the Paper component as `paper.jar`
 * (`:helix-addon-guardlab-paper`, like lobby/lobby-paper); without that module a prebuilt `libs/paper.jar`
 * is taken, and without either the HXA ships the node side alone.
 */
val paperModule = rootProject.findProject(":helix-addon-guardlab-paper")

val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-guardlab-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    if (paperModule != null) {
        from(paperModule.tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
            rename { "paper.jar" }
        }
    } else {
        from(layout.projectDirectory.dir("libs")) {
            include("paper.jar")
        }
    }
}
