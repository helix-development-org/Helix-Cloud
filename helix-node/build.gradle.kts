import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    application
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(rootProject.project("helix-api"))
    api(rootProject.project("helix-addon-sdk"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("com.zaxxer:HikariCP:5.1.0")
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    implementation("org.mongodb:mongodb-driver-sync:5.2.1")
    implementation("io.ktor:ktor-server-core-jvm:3.5.1")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.1")
    implementation("io.ktor:ktor-server-auth-jvm:3.5.1")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.5.1")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.5.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.5.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.1")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.5.1")
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
        attributes["Implementation-Version"] = version
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