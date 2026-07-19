import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
}

allprojects {
    group = "org.helix"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

tasks.register("verifyKDocAvailability") {
    group = "verification"
    description = "Verifies that public Kotlin declarations in main sources have KDoc."

    doLast {
        val declarationPattern = Regex(
            "^(data class|class|interface|enum class|object|value class|inline fun|fun)\\s|^@JvmInline$"
        )
        val missing = mutableListOf<String>()

        subprojects
            .map { it.layout.projectDirectory.dir("src/main/kotlin").asFile }
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }
            .forEach { sourceFile ->
                var previousSignificantLine: String? = null
                var secondPreviousSignificantLine: String? = null

                sourceFile.readLines().forEachIndexed { index, line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) {
                        return@forEachIndexed
                    }

                    if (declarationPattern.containsMatchIn(trimmedLine)) {
                        val hasKDoc = previousSignificantLine?.endsWith("*/") == true ||
                            secondPreviousSignificantLine?.endsWith("*/") == true
                        if (!hasKDoc) {
                            missing += "${sourceFile.relativeTo(rootDir)}:${index + 1}: $trimmedLine"
                        }
                    }

                    secondPreviousSignificantLine = previousSignificantLine
                    previousSignificantLine = trimmedLine
                }
            }

        if (missing.isNotEmpty()) {
            throw GradleException("Missing KDoc:\n${missing.joinToString(separator = "\n")}")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        "implementation"("org.slf4j:slf4j-api:2.0.18")
        "testImplementation"(kotlin("test"))
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(24)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.named("check") {
        dependsOn(rootProject.tasks.named("verifyKDocAvailability"))
    }
}
