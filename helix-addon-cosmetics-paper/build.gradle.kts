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
    // packetevents is its own plugin on the servers (shipped by the guard addon); optional here
    compileOnly(libs.packetevents.spigot)
    implementation(libs.kotlinx.serialization.json)
    implementation(rootProject.project("helix-wire"))
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

// The cosmetics resource pack (Blockbench exports in src/pack/resources/cosmetics) is
// assembled deterministically at build time and bundled into the HXA.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Assembles pack.zip from the Blockbench cosmetic models and textures."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.cosmetics.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    val source = layout.projectDirectory.dir("src/pack/resources/cosmetics")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath, source.asFile.absolutePath) })
    inputs.dir(source)
    outputs.file(output)
}
