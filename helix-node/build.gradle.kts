import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    application
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(rootProject.project("helix-api"))
    api(rootProject.project("helix-addon-sdk"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

application {
    mainClass.set("org.helix.node.launcher.LauncherMain")
}

// Build the React (shadcn/ui) dashboard and bundle it into the jar resources,
// so the Launcher keeps serving the dashboard from the classpath.
val dashboardDir = layout.projectDirectory.dir("../helix-dashboard")
val buildDashboard by tasks.registering(Exec::class) {
    workingDir = dashboardDir.asFile
    // CI=true keeps pnpm fully non-interactive (no node_modules purge prompt,
    // which aborts without a TTY when invoked from Gradle).
    environment("CI", "true")
    commandLine("sh", "-c", "pnpm install --frozen-lockfile && pnpm build")
    inputs.dir(dashboardDir.dir("src"))
    inputs.file(dashboardDir.file("package.json"))
    inputs.file(dashboardDir.file("pnpm-lock.yaml"))
    inputs.file(dashboardDir.file("index.html"))
    inputs.file(dashboardDir.file("vite.config.ts"))
    outputs.dir(dashboardDir.dir("dist"))
}

tasks.processResources {
    dependsOn(buildDashboard)
    from(dashboardDir.dir("dist")) { into("dashboard") }
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
    // Apache-2.0 attribution ships with every binary distribution; unique
    // names avoid being shadowed by dependency LICENSE/NOTICE entries.
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-HelixCloud" }
    }
    from(rootProject.layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
        rename { "NOTICE-HelixCloud" }
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}