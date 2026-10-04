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
    // NodeTranslations (org.helix.api.i18n) — bundled into the fat jar below.
    implementation(rootProject.project("helix-api"))
    implementation(rootProject.project("helix-wire"))
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

// Villager resource pack: the Blockbench models in src/pack/resources/villagers are converted at build time into
// item models per rig part (pack.zip, bundled into the HXA) plus a rig description bundled into the plugin jar.
sourceSets {
    create("pack")
}

dependencies {
    "packImplementation"(sourceSets.main.get().output)
    "packImplementation"(libs.kotlinx.serialization.json)
    testImplementation(sourceSets["pack"].output)
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Assembles pack.zip with the villager models and the rig description."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.npc.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    val rigs = layout.buildDirectory.file("pack/rigs/helix_villager/rigs.json")
    val models = layout.projectDirectory.dir("src/pack/resources/villagers")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(output.get().asFile.absolutePath, rigs.get().asFile.absolutePath, models.asFile.absolutePath)
    })
    inputs.files(fileTree(models))
    outputs.file(output)
    outputs.file(rigs)
}

tasks.named<Jar>("jar") {
    dependsOn(generatePack)
    from(layout.buildDirectory.dir("pack/rigs"))
}
