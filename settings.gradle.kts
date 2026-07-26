plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Helix-Cloud"

// IGui (resource-pack font GUIs) lives as a sibling checkout and is wired
// in as a composite build: de.tytoss:igui resolves to ../IGui sources.
includeBuild("../IGui") {
    dependencySubstitution {
        substitute(module("de.tytoss:igui")).using(project(":"))
    }
}

include(
    "helix-api",
    "helix-node",
    "helix-wrapper",
    "helix-bridge-paper",
    "helix-bridge-velocity",
    "helix-addon-sdk",
    "helix-addon-example",
    "helix-addon-bans",
    "helix-addon-bettermsgs",
    "helix-addon-bettermsgs-paper",
    "helix-addon-permissions",
    "helix-addon-friends",
    "helix-addon-clan",
    "helix-addon-guard",
    "helix-addon-guard-paper",
    "helix-addon-tablist",
    "helix-addon-scoreboard",
    "helix-addon-chat",
    "helix-addon-economy",
    "helix-addon-moderation",
    "helix-addon-teamutils",
    "helix-addon-discord",
    "helix-addon-motd",
    "helix-addon-npc",
    "helix-addon-npc-paper"
)
