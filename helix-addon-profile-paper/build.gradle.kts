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
    compileOnly(libs.paper.api)
    // Only compileOnly at runtime (Paper provides it), but the texture-mapping test constructs a
    // real Adventure Key, so the test task needs the actual classes on its runtime classpath.
    testImplementation(libs.paper.api)
    // helix-gui's own PostgreSQL texture database is unused — this plugin's NodeGuiTextureDatabase
    // proxies texture storage through the node's actions instead of a direct DB connection (see
    // helix-cloud-project convention: game servers never talk to a database directly) — so the
    // driver and pool stay out of the plugin jar.
    implementation(project(":helix-gui")) {
        exclude(group = "org.postgresql")
        exclude(group = "com.zaxxer")
    }
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

// The profile menu's title uses IGui's font-based DisplayBuilder (centeredText), which needs a
// resource-pack font under its own namespace (spacing glyphs + per-row vanilla-ascii text fonts) —
// generated deterministically at build time and bundled into the HXA; the node merges it into the
// network pack.
sourceSets {
    create("pack")
}

val generatePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Assembles the profile menu's font resource pack (pack.zip)."
    classpath = sourceSets["pack"].runtimeClasspath
    mainClass.set("org.helix.addons.profile.pack.PackGeneratorKt")
    val output = layout.buildDirectory.file("pack/pack.zip")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
    outputs.file(output)
}
