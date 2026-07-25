import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

repositories {
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
    }
}

// packetevents ships as a separate Bukkit plugin IGuard depends on — it is
// bundled into the HXA under paper/ and installed alongside on every server
val packetevents: Configuration by configurations.creating

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
    packetevents("com.github.retrooper:packetevents-spigot:2.13.0")
}

/**
 * Builds IGuard's shaded Paper plugin from the sibling checkout. IGuard
 * resolves `de.tytoss:igui` and `org.fsqrt.rune:base` from mavenLocal —
 * IGui is published through the composite build (no nested Gradle process,
 * which would deadlock on the shared project locks); rune must be present
 * in mavenLocal once.
 */
val buildIGuard by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the IGuard anticheat shadow jar from ../IGuard."
    dependsOn(gradle.includedBuild("IGui").task(":publishToMavenLocal"))
    workingDir = rootProject.projectDir.resolve("../IGuard")
    commandLine("./gradlew", "-q", "--no-daemon", "shadowJar")
}

val buildIGuardVelocity by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds IGuard's Velocity enforcement plugin from ../IGuard/velocity."
    // serialized behind buildIGuard: concurrent nested Gradle builds fight
    // over the shared ~/.gradle caches
    mustRunAfter(buildIGuard)
    workingDir = rootProject.projectDir.resolve("../IGuard/velocity")
    commandLine(rootProject.projectDir.resolve("../IGuard/gradlew").absolutePath, "-q", "--no-daemon", "build")
}

/**
 * Merges the packetevents plugin and its API modules into one runnable
 * plugin jar (the maven artifact is the thin spigot module).
 */
val packetEventsJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the bundled packetevents plugin jar."
    archiveFileName.set("packetevents.jar")
    destinationDirectory.set(layout.buildDirectory.dir("packetevents"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(packetevents.map { if (it.isFile && it.name.endsWith(".jar")) zipTree(it) else it })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

/**
 * Packages the addon as HXA: the node addon (panel-configurable IGuard
 * settings) plus the IGuard Paper plugin, its Velocity enforcement plugin
 * and the bundled packetevents dependency.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages Helix-Guard as .hxa (addon + IGuard paper/velocity plugins + packetevents)."
    dependsOn(tasks.named<Jar>("jar"), buildIGuard, buildIGuardVelocity)
    archiveFileName.set("helix-guard-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(rootProject.projectDir.resolve("../IGuard/build/libs/IGuard-1.0.0-SNAPSHOT.jar")) {
        rename { "paper.jar" }
    }
    from(rootProject.projectDir.resolve("../IGuard/velocity/build/libs/iguard-velocity.jar")) {
        rename { "velocity.jar" }
    }
    from(packetEventsJar) {
        into("paper")
    }
}
