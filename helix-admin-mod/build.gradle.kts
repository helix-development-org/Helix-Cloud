plugins {
    id("fabric-loom") version "1.18.2"
    kotlin("jvm") version "2.3.10"
}

group = "org.helix"
version = "${property("mod_version")}+mc${property("minecraft_version")}"
base { archivesName.set("helix-admin") }

repositories {
    // MCEF Modern (Chromium im Spiel) über Modrinth-Maven
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("maven.modrinth:mcef-modern:${property("mcef_modern_version")}")
    // JCEF-API (CefMessageRouter für die JS-Brücke) – liefert MCEF Modern zur Laufzeit mit
    modCompileOnly("me.friwi:jcef-api:jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.test { useJUnitPlatform() }
