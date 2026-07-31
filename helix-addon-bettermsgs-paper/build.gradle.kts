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
}

dependencies {
    implementation(rootProject.project("helix-api"))
    compileOnly(libs.paper.api)
    // helix-gui is not bundled: the shared Helix-GUIs plugin (helix-addon-guis) installs the one
    // real IGui instance and provides its classes (and kotlinx-coroutines-core) at runtime via the
    // plugin.yml `depend` relationship (Bukkit's plugin classloader delegates to declared
    // dependencies) — this plugin only needs the library's types to compile against.
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
            // No own Kotlin runtime (kotlin-stdlib, kotlinx-*): the Helix-GUIs plugin bundles it
            // and provides it through the plugin.yml `depend` relationship. Bundling a second
            // stdlib copy makes the JVM see kotlin.coroutines.CoroutineContext from two loaders
            // in one inheritance chain and refuse to link (LinkageError on enable).
            .filter { it.isFile && !it.name.startsWith("kotlin") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// The BetterMSGs resource pack (self-drawn phone/chat textures + fonts) is
// generated deterministically at build time and bundled into the HXA.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Draws the BetterMSGs textures and assembles pack.zip."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.bettermsgs.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
    outputs.file(output)
}
