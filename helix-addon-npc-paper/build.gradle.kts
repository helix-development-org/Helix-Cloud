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
    // INpc is vendored as its prebuilt, Mojang-mapped library jar. It carries
    // no server classes; modern Paper (1.21.11) resolves its Mojang-named NMS
    // references at runtime. Building it here would drag in paperweight +
    // paperDevBundle + toolchain 25, so the prebuilt jar is bundled instead.
    implementation(files("libs/inpc-1.0.0-SNAPSHOT.jar"))
    // INpc's own runtime dependencies (declared api in its build) — vendored
    // as normal maven deps because the files(...) jar carries no metadata.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

/**
 * Fat plugin jar: bundles the vendored INpc framework and the kotlinx
 * runtime it needs, so the HXA's paper.jar drops onto a Paper server with no
 * further installs. paper-api and the Kotlin stdlib are provided by the
 * server, so they stay out of the jar.
 */
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
