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
    implementation(rootProject.project("helix-wire"))
    compileOnly(libs.paper.api)
    // helix-gui is not bundled: the shared Helix-GUIs plugin (helix-addon-guis) installs the one
    // real IGui instance and provides its classes at runtime via the plugin.yml `depend` relationship.
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
            // plugin.yml `depend` relationship. A second stdlib copy makes the JVM refuse to link.
            .filter { it.isFile && !it.name.startsWith("kotlin") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// The lobby resource pack (navigator art painted in Blockbench, src/pack/resources/navigator) is
// assembled deterministically at build time and bundled into the HXA.
sourceSets {
    create("pack")
}

dependencies {
    "packImplementation"(project(":helix-gui"))
    "packImplementation"(sourceSets.main.get().output)
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Assembles pack.zip with the navigator art."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.lobby.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    val art = layout.projectDirectory.dir("src/pack/resources/navigator")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath, art.asFile.absolutePath) })
    inputs.dir(art)
    outputs.file(output)
}
