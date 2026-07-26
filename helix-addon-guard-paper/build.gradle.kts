import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // packetevents ships as its own Bukkit plugin (bundled into the HXA under paper/), so the API
    // is compile-only here; the movement tests exercise packetevents types, hence the test dependency.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    testImplementation("com.github.retrooper:packetevents-spigot:2.13.0")
    // IGui resolves through the composite build (../IGui). Its PostgreSQL texture database is unused —
    // IGuard ships a file-backed one — so the driver and pool stay out of the plugin jar.
    // kotlinx-coroutines-core arrives transitively as an api dependency of IGui.
    implementation("de.tytoss:igui:1.0.0-SNAPSHOT") {
        exclude(group = "org.postgresql")
        exclude(group = "com.zaxxer")
    }
    // Rune snapshot/copy model for the world reconstruction used by the incident replay (vendored jar).
    implementation(files("libs/rune-base-0.0.1.jar"))
    // JSON runtime for the Helix node action bridge + the file-backed GUI texture store.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// The IGuard panel resource pack (header/background glyphs + fonts) is
// generated deterministically at build time and bundled into the HXA; the
// node merges it into the network pack.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Draws the IGuard panel textures and assembles pack.zip."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("de.tytoss.iguard.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
    outputs.file(output)
}
