plugins {
    id("fabric-loom") version "1.18.0-alpha.16"
}

description = "The mod for Fabric 26.x: emote rendering on the client, identity relay on the server."

base { archivesName = "DGGChat-Fabric" }

// The Minecraft version this build targets: 26.2 (the main pack and the dev server)
// unless -Pmc=26.3 (the Hardcore server and its pack). The handful of classes that
// differ between them live in src/mc<version>/java; everything else is shared.
val mc = (findProperty("mc") as String?) ?: "26.2"
val fabricVersions = mapOf(
    "26.2" to ("0.19.3" to "0.157.0+26.2"),
    "26.3" to ("0.20.1" to "0.165.0+26.3"),
)
val (loaderVersion, fabricApiVersion) = fabricVersions[mc]
    ?: error("no Fabric versions for Minecraft $mc; add them to fabricVersions")
sourceSets.main { java.srcDir("src/mc$mc/java") }
if (mc != "26.2") {
    // Its own build directory, so the two jars never share a libs/ folder, and a
    // version suffix, so they never share a name either.
    layout.buildDirectory = layout.projectDirectory.dir("build/mc$mc")
    version = "${project.version}+$mc"
}

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    // 26.x ships unobfuscated and publishes no Mojang mappings; the identity
    // intermediary comes from scripts/setup-262-mappings.sh <version>.
    mappings("net.fabricmc:intermediary:$mc")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

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
    inputs.property("minecraft", mc)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft" to mc)
    }
}
