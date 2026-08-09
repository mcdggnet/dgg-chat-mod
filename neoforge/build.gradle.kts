plugins {
    id("net.neoforged.moddev") version "2.0.143"
}

description = "The mod: emote rendering on the client, identity relay on the server."

// Otherwise the artifact is named after the Gradle module ("neoforge-x.y.z.jar"),
// which is meaningless sitting in a mods folder.
base { archivesName = "DGGChat-NeoForge" }

neoForge {
    // NeoForge 21.1.x is the Minecraft 1.21.1 line, which is what ATM10 runs.
    version = "21.1.248"
}

dependencies {
    // The identity SPI. jarJar bundles it into the mod jar, since a server has no
    // other way to supply it, while dggauth takes it as compileOnly from Packages.
    implementation(project(":api"))
    jarJar(project(":api"))
}

// neoforge.mods.toml declares `version = "${version}"`, which is inert until something
// substitutes it. Without this the mod reports a literal "${version}" to the loader.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "dgg-chat-neoforge"
            pom {
                name = "DGG Chat (NeoForge)"
                description = "Destiny.gg emotes and flair in Minecraft chat."
            }
        }
    }
}

// ModDevGradle's first run downloads and decompiles Minecraft. That is slow and
// disk-hungry, and it is the whole reason this module is kept separate: building
// :api never triggers any of it.
