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
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
    maven {
        name = "extendedclip"
        url = uri("https://repo.extendedclip.com/releases/")
    }
}

dependencies {
    implementation(rootProject.project("helix-api"))
    implementation(rootProject.project("helix-wire"))
    compileOnly(libs.paper.api)
    // Optional at runtime (softdepend): used to rewrite PLAYER_INFO names so a nick shows in the
    // name tag above the player. Ships as its own plugin via the guard HXA.
    compileOnly(libs.packetevents.spigot)
    // Optional at runtime (softdepend): Vault economy provider and
    // PlaceholderAPI expansion register only when the plugins are present.
    compileOnly(libs.vault.api)
    compileOnly(libs.placeholderapi)
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
