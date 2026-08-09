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

// The two platform-free modules are named for what they publish as, not for the directory
// they sit in. jarJar identifies an embedded library by its Gradle module coordinates and
// not by the publication's artifactId, so calling these ":api" and ":core" would ship them
// inside the mod jar as net.mcdgg:api and net.mcdgg:core. FML deduplicates jar-in-jar
// libraries by group and artifact, so any other mod embedding something under names that
// generic would collide with these and one of the two would lose.

// The identity SPI another mod implements. No Minecraft, no NeoForge, so dggauth can
// compile against it without dragging the game in.
include(":dgg-chat-api")
project(":dgg-chat-api").projectDir = file("api")

// chat-gui's rendering rules, ported. Also platform-free, which is the point: the parts
// that have to match destiny.gg exactly are the parts worth testing without a game around.
include(":dgg-chat-core")
project(":dgg-chat-core").projectDir = file("core")

// The mod itself: emote rendering on the client, identity relay on the server.
include(":neoforge")
