pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

// Eigenständiger Build (Fabric Loom), nicht Teil des Helix-Multimodul-Builds:
//   cd helix-admin-mod && ./gradlew build   (eigener Wrapper: Loom 1.18 braucht Gradle 9.7)
rootProject.name = "helix-admin-mod"
