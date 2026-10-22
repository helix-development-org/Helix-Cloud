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
    compileOnly(libs.paper.api)
    // packetevents is its own plugin on the servers (shipped by the guard addon); required for the camera packet
    compileOnly(libs.packetevents.spigot)
    implementation(libs.kotlinx.serialization.json)
    implementation(rootProject.project("helix-wire"))
    // the cinematic library is installed and shared by this plugin (like Helix-GUIs does for IGui)
    implementation(project(":helix-library-cinematic"))
    // the library's stand-in NPCs run on INpc (vendored jar) and coroutines
    implementation(files("../helix-addon-npc-paper/libs/inpc-1.0.0-SNAPSHOT.jar"))
    implementation(libs.kotlinx.coroutines.core)
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
            .filter { it.isFile }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// The cinematic resource pack (the full-screen black glyph for fades) is generated
// deterministically at build time and bundled into the HXA.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates the fade font and assembles pack.zip."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.cinematic.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
    outputs.file(output)
}
