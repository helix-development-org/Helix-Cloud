import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Resource-pack font GUI library (formerly the standalone IGui project, vendored in as a
// regular module so the whole monorepo builds from one checkout with no sibling dependency).

// paper-api is not published to Maven Central; every other Paper-dependent module declares this
// same repository for that reason.
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnlyApi(libs.paper.api)
    api(libs.postgresql)
    api(libs.hikaricp)
    api(libs.kotlinx.coroutines.core)
}

// Consumed by Paper plugins (helix-addon-guard-paper, helix-addon-bettermsgs-paper), which run on
// Paper's supported Java 21 — matches helix-api's own release target for the same reason.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}
