import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "org.helix"
    version = "0.85.0"

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

/** Addon modules packaged into the release bundle. */
val addonModules = listOf(
    "helix-addon-example",
    "helix-addon-guis",
    "helix-addon-bans",
    "helix-addon-bettermsgs",
    "helix-addon-permissions",
    "helix-addon-friends",
    "helix-addon-clan",
    "helix-addon-guard",
    "helix-addon-labymod",
    "helix-addon-tablist",
    "helix-addon-scoreboard",
    "helix-addon-chat",
    "helix-addon-economy",
    "helix-addon-moderation",
    "helix-addon-teamutils",
    "helix-addon-discord",
    "helix-addon-motd",
    "helix-addon-npc",
    "helix-addon-nick",
    "helix-addon-stats",
    "helix-addon-parties",
    "helix-addon-maprotation",
    "helix-addon-profile",
    "helix-addon-subtitles",
    "helix-addon-cosmetics",
)

tasks.register("releaseBundle") {
    group = "distribution"
    description = "Collects the release artifacts (Launcher.jar, all addon HXAs) with SHA-256 checksums."
    dependsOn(":helix-node:jar")
    addonModules.forEach { dependsOn(":$it:packageHxa") }

    doLast {
        val releaseDirectory = layout.buildDirectory.dir("release").get().asFile
        val artifacts = buildList {
            add(
                project(":helix-node").layout.buildDirectory.file("libs/Launcher.jar").get().asFile
                    to "Launcher.jar",
            )
            addonModules.forEach { module ->
                val hxaName = "helix-${module.removePrefix("helix-addon-")}-$version.hxa"
                add(
                    project(":$module").layout.buildDirectory
                        .file("distributions/$hxaName").get().asFile to hxaName,
                )
            }
        }
        // verify everything BEFORE wiping the previous release, so a failed
        // build never leaves an empty release directory behind
        artifacts.forEach { (source, _) ->
            require(source.isFile) { "release artifact missing: $source" }
        }
        releaseDirectory.deleteRecursively()
        releaseDirectory.mkdirs()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val checksums = StringBuilder()
        artifacts.forEach { (source, targetName) ->
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
        "implementation"(rootProject.libs.slf4j.api)
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
