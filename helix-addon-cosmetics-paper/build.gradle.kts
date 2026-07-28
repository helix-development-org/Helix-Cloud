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
    compileOnly(libs.paper.api)
    implementation(libs.kotlinx.serialization.json)
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

// The cosmetics resource pack (self-drawn wing/headwear textures + hand-authored
// item models) is generated deterministically at build time and bundled into the HXA.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Draws the cosmetic textures, writes the item models and assembles pack.zip."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.cosmetics.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
    outputs.file(output)
}
