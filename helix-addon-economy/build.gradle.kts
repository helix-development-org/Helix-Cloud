import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
    // Provided by the node classloader at runtime (helix-node bundles both); only needed to compile.
    compileOnly(libs.hikaricp)
    compileOnly(libs.postgresql)
    // The PostgreSQL repository test runs against HELIX_TEST_POSTGRES_URL when set and is skipped otherwise.
    testImplementation(libs.hikaricp)
    testImplementation(libs.postgresql)
}

/**
 * Packages the addon as HXA: a zip with addon.json and addon.jar.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-economy-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
}

tasks.withType<Test>().configureEach {
    System.getenv("HELIX_TEST_POSTGRES_URL")?.let { environment("HELIX_TEST_POSTGRES_URL", it) }
}
