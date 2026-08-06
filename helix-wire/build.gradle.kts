import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(rootProject.project("helix-api"))
    api(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutines.core)
}

// Consumed by the node and by the platform bridges (Paper/Velocity on
// Java 21) and shaded into their jars — plain JDK sockets, no Netty, so it
// never clashes with a platform's own Netty version. Targets 21.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}
