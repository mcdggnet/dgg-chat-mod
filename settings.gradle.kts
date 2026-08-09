pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}
// NeoForge 21.1 / MC 1.21.1 must be built with a Java 21 toolchain, and Fedora ships no
// java-21 package. The foojay resolver lets Gradle download one on demand, keeping the
// requirement in the build rather than in any one machine's setup or in CI's.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // No repositoriesMode restriction: ModDevGradle must register Mojang's meta and
    // library repositories itself to resolve minecraft-dependencies, and both
    // FAIL_ON_PROJECT_REPOS and PREFER_SETTINGS prevent that.
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "neoforged" }
    }
}
rootProject.name = "dgg-chat-mod"

// Platform-free: the identity SPI another mod implements. No Minecraft, no NeoForge,
// so dggauth can compile against it without dragging the game in.
include(":api")
// The mod itself: emote rendering on the client, identity relay on the server.
include(":neoforge")
