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
    // helix-gui is not bundled: the shared Helix-GUIs plugin installs the one real IGui instance and
    // provides its classes (and the Kotlin runtime) at runtime via the plugin.yml `depend` relationship.
    compileOnly(project(":helix-gui"))
    testImplementation(libs.paper.api)
    testImplementation(project(":helix-gui"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// The jar is the addon's paper.jar: helix-addon-feedback's packageHxa takes
// `project(":helix-addon-feedback-paper").tasks.named<Jar>("jar")` and renames it to paper.jar
// (same as helix-addon-lobby with :helix-addon-lobby-paper).
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            // No own Kotlin runtime: Helix-GUIs bundles it; a second stdlib copy breaks linking.
            .filter { it.isFile && !it.name.startsWith("kotlin") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
