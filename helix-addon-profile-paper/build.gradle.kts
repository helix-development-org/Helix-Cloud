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
    // real IGui instance and provides its classes at runtime via the plugin.yml `depend` relationship
    // (Bukkit's plugin classloader delegates to declared dependencies) — this plugin only needs the
    // library's types to compile against.
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
            .filter { it.isFile }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
