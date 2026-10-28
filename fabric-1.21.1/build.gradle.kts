plugins {
    id("fabric-loom") version "1.18.0-alpha.16"
}

description = "The mod for Fabric 1.21.1 (the Cobble server and its pack): emote rendering on the client, identity relay on the server."

// Same name as the 26.x jar, with the Minecraft version as a suffix, so the packs pick
// the build for their version the way they pick fabric/'s 26.3 one.
base { archivesName = "DGGChat-Fabric" }
version = "${project.version}+1.21.1"

// A module of its own rather than another -Pmc target of fabric/: almost every class
// touches the font, chat or networking APIs 26.x rewrote, so this is the NeoForge mod's
// 1.21.1 code with Fabric's entry points, payload registration and mixins in place of
// NeoForge's events (see DggChatFabric, DggChatFabricClient, ChatComponentMixin,
// GameRendererMixin), not fabric/ with a compat package.

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    // The loader and Fabric API COBBLEVERSE 1.7.42 ships.
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.14+1.21.1")

    // Nested into the mod jar, as jarJar does for NeoForge.
    implementation(project(":dgg-chat-api"))
    implementation(project(":dgg-chat-core"))
    include(project(":dgg-chat-api"))
    include(project(":dgg-chat-core"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
