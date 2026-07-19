import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
}

allprojects {
    group = "org.helix"
    version = "0.2.0"

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

tasks.register("releaseBundle") {
    group = "distribution"
    description = "Collects the release artifacts (Launcher.jar, example addon) with SHA-256 checksums."
    dependsOn(":helix-node:jar", ":helix-addon-example:packageHxa", ":helix-addon-bans:packageHxa")

    doLast {
        val releaseDirectory = layout.buildDirectory.dir("release").get().asFile
        releaseDirectory.deleteRecursively()
        releaseDirectory.mkdirs()
        val artifacts = listOf(
            project(":helix-node").layout.buildDirectory.file("libs/Launcher.jar").get().asFile
                to "Launcher.jar",
            project(":helix-addon-example").layout.buildDirectory
                .file("distributions/helix-example-$version.hxa").get().asFile
                to "helix-example-$version.hxa",
            project(":helix-addon-bans").layout.buildDirectory
                .file("distributions/helix-bans-$version.hxa").get().asFile
                to "helix-bans-$version.hxa",
        )
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val checksums = StringBuilder()
        artifacts.forEach { (source, targetName) ->
            require(source.isFile) { "release artifact missing: $source" }
            val target = releaseDirectory.resolve(targetName)
            source.copyTo(target, overwrite = true)
            digest.reset()
            val hash = digest.digest(target.readBytes())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            checksums.append(hash).append("  ").append(targetName).append('\n')
        }
        releaseDirectory.resolve("SHA-256SUMS").writeText(checksums.toString())
        println("Release bundle written to $releaseDirectory")
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
