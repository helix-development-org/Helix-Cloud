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
    implementation(rootProject.project("helix-api"))
    implementation(rootProject.project("helix-wire"))
    compileOnly(libs.paper.api)
    // packetevents runs as its own backend plugin (declared in plugin.yml `depend`); used to open
    // the writable-book editor for long/multiline value editing. API-only at compile time.
    compileOnly(libs.packetevents.spigot)
    // helix-gui is not bundled: the shared Helix-GUIs plugin installs the one real IGui instance
    // and provides its classes (and kotlinx-coroutines-core) at runtime via the plugin.yml `depend`
    // relationship — this plugin only needs the library's types to compile against.
    compileOnly(project(":helix-gui"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            // No own Kotlin runtime: the Helix-GUIs plugin bundles it and provides it through the
            // plugin.yml `depend` relationship. A second stdlib copy makes the JVM see coroutine
            // types from two loaders in one inheritance chain and refuse to link (LinkageError).
            .filter { it.isFile && !it.name.startsWith("kotlin") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// The translations editor resource pack (dirt background glyph + preview row
// fonts) is generated deterministically at build time and bundled into the HXA.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Draws the dirt background texture and assembles pack.zip."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.translations.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
    outputs.file(output)
}
