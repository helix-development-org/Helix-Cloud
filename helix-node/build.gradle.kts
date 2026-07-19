import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    application
}

dependencies {
    api(rootProject.project("helix-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.tomlj:tomlj:1.1.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

application {
    mainClass.set("org.helix.node.launcher.LauncherMain")
}

val wrapperJar = rootProject.project("helix-wrapper").tasks.named<Jar>("jar")
val paperBridgeJar = rootProject.project("helix-bridge-paper").tasks.named<Jar>("jar")
val velocityBridgeJar = rootProject.project("helix-bridge-velocity").tasks.named<Jar>("jar")

tasks.named<Jar>("jar") {
    archiveFileName.set("Launcher.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile }
            .map { zipTree(it) }
    })
    from(wrapperJar) {
        into("helix-internal")
        rename { "Wrapper.jar" }
    }
    from(paperBridgeJar) {
        into("helix-internal/bridges")
        rename { "HelixPaperBridge.jar" }
    }
    from(velocityBridgeJar) {
        into("helix-internal/bridges")
        rename { "HelixVelocityBridge.jar" }
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
