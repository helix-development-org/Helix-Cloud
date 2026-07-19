import org.gradle.jvm.tasks.Jar

dependencies {
    implementation(rootProject.project("helix-api"))
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "org.helix.wrapper.WrapperMain"
    }
}
