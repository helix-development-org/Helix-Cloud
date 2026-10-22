import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(rootProject.project("helix-addon-sdk"))
    // Provided by the node classloader at runtime (helix-node bundles both); only needed to compile.
    compileOnly(libs.hikaricp)
    compileOnly(libs.postgresql)
}

/**
 * Merges the translation fragments `src/main/lang/<locale>/<module>.json` into one flat file per
 * locale (`lang/<locale>.json`, the layout LangResources loads). Fragments keep the node side and the
 * CityBuild Paper plugin (which merges the same directory for its bundled fallback) free of merge
 * conflicts; a key defined in two fragments of the same locale fails the build.
 */
val mergeLang by tasks.registering {
    group = "build"
    description = "Merges src/main/lang/<locale>/*.json into lang/<locale>.json."
    val source = layout.projectDirectory.dir("src/main/lang")
    val target = layout.buildDirectory.dir("generated/lang")
    inputs.dir(source)
    outputs.dir(target)
    doLast {
        val slurper = groovy.json.JsonSlurper()
        val out = target.get().asFile.resolve("lang").apply { deleteRecursively(); mkdirs() }
        source.asFile.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { localeDir ->
            val merged = sortedMapOf<String, String>()
            localeDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { fragment ->
                @Suppress("UNCHECKED_CAST")
                val entries = slurper.parse(fragment) as Map<String, Any?>
                entries.forEach { (key, value) ->
                    if (merged.containsKey(key)) {
                        throw GradleException("duplicate lang key '$key' in ${localeDir.name}/${fragment.name}")
                    }
                    merged[key] = value.toString()
                }
            }
            out.resolve("${localeDir.name}.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(merged)) + "\n")
        }
    }
}

sourceSets.named("main") {
    resources.srcDir(mergeLang.map { layout.buildDirectory.dir("generated/lang").get() })
}

/**
 * Packages the addon as HXA: addon.json + addon.jar plus the CityBuild Paper plugin
 * (`libs/paper.jar`) and its resource pack (`libs/pack.zip`). Both are built by the CityBuild
 * repository (`./gradlew deployHelix`) and dropped into `libs/`; when they are missing the HXA still
 * packages the node side alone.
 */
val packageHxa by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the addon as .hxa (addon.json + addon.jar + paper.jar + pack.zip)."
    dependsOn(tasks.named<Jar>("jar"))
    archiveFileName.set("helix-citybuild-$version.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "addon.jar" }
    }
    from(layout.projectDirectory.dir("libs")) {
        include("paper.jar", "pack.zip")
    }
}
