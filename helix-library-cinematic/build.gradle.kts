import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Cinematic camera library (scenes of shots: paths, look targets, easing, fades, timed events) for Paper
// plugins. Like helix-gui: one plugin (helix-addon-cinematic-paper, "HelixCinematic") installs it and shares the
// instance through Bukkit's ServicesManager; other plugins `depend: [HelixCinematic]` and compile against this
// module compileOnly.

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
    compileOnlyApi(libs.paper.api)
    // the camera packet; packetevents runs as its own plugin on every server (guard addon)
    compileOnly(libs.packetevents.spigot)
    testImplementation(libs.paper.api)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}
