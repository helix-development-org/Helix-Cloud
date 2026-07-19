plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Helix-Cloud"

include(
    "helix-api",
    "helix-node",
    "helix-wrapper",
    "helix-bridge-paper",
    "helix-bridge-velocity",
    "helix-addon-sdk",
    "helix-addon-example",
    "helix-addon-bans",
    "helix-addon-permissions",
    "helix-addon-friends",
    "helix-addon-tablist",
    "helix-addon-chat",
    "helix-addon-economy",
    "helix-addon-moderation",
    "helix-addon-teamutils"
)
