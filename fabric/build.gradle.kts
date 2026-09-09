plugins {
    id("fabric-loom") version "1.18.0-alpha.16"
}

description = "The mod for Fabric 26.x: emote rendering on the client, identity relay on the server."

base { archivesName = "DGGChat-Fabric" }

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    // 26.2 ships unobfuscated and publishes no Mojang mappings; the identity
    // intermediary comes from clientdev/companion/scripts/setup-262-mappings.sh.
    mappings("net.fabricmc:intermediary:26.2")
    modImplementation("net.fabricmc:fabric-loader:0.19.3")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.157.0+26.2")

    // Nested into the mod jar, as jarJar does for NeoForge. Loom wraps each plain
    // library in a generated fabric.mod.json; a server has no other way to get them.
    implementation(project(":dgg-chat-api"))
    implementation(project(":dgg-chat-core"))
    include(project(":dgg-chat-api"))
    include(project(":dgg-chat-core"))
}

java {
    // 26.2 is Java 25 bytecode; the root build's 21 (right for NeoForge 1.21.1) is
    // overridden here.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
