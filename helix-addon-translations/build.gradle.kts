import org.gradle.jvm.tasks.Jar

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
}

/**
 * Packages the addon as HXA: addon.json + addon.jar plus the Paper-side
 * `/translationsmenu` GUI (paper.jar) built by :helix-addon-translations-paper.
 * The ledger art ships with the Helix-GUIs pack.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-translations-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(project(":helix-addon-translations-paper").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "paper.jar" }
    }
}
