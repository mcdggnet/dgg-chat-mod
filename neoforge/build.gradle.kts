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

    // Declaring the mod's source set is what puts the mixin config and the font definition
    // in front of the loader during a dev run.
    mods {
        register("dggchat") { sourceSet(sourceSets["main"]) }
    }

    // Puts Minecraft on the test classpath. What that buys is a real test of the chat
    // rewrite against real Component trees, which is the part of this mod nobody can check
    // by reading it and the part players see every message.
    unitTest {
        enable()
        testedMod = mods.getByName("dggchat")
    }

    // ./gradlew :neoforge:runClient  and  :neoforge:runServer
    // The server run is the cheap check: it proves the mod loads, the payload registers and
    // the identity SPI resolves, none of which needs a window. Accepting Minecraft's EULA in
    // run/eula.txt is left to whoever runs it.
    runs {
        register("client") { client() }
        register("server") { server() }
    }
}

dependencies {
    // The identity SPI. jarJar bundles it into the mod jar, since a server has no
    // other way to supply it, while dggauth takes it as compileOnly from Packages.
    implementation(project(":dgg-chat-api"))
    implementation(project(":dgg-chat-core"))
    jarJar(project(":dgg-chat-api"))
    jarJar(project(":dgg-chat-core"))

    // jarJar covers the shipped jar, but a dev run launches from the source sets and never
    // sees it. Without these, runClient and runServer die constructing the mod with
    // NoClassDefFoundError on the SPI, which says nothing about whether the real jar works.
    // Named as a string because ModDevGradle registers the configuration too late for
    // Gradle to generate a Kotlin accessor for it.
    add("additionalRuntimeClasspath", project(":dgg-chat-api"))
    add("additionalRuntimeClasspath", project(":dgg-chat-core"))
    // Gson comes from Minecraft at runtime, but :core declares it compileOnly, so the dev
    // run needs it named somewhere.
    add("additionalRuntimeClasspath", "com.google.code.gson:gson:2.10.1")

    testImplementation(project(":dgg-chat-core"))
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
