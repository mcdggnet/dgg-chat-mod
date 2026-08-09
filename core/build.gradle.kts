description = "chat-gui's rendering rules, ported and tested. No Minecraft, no NeoForge."

dependencies {
    // Minecraft already ships Gson, so the mod gets it at runtime for free. compileOnly
    // keeps a second copy out of the jar, where it could only cause a version clash.
    compileOnly("com.google.code.gson:gson:2.10.1")

    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 omits the launcher from the test runtime classpath.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "dgg-chat-core"
            pom {
                name = "DGG Chat Core"
                description = "Emote matching, flair resolution and username colour, ported from chat-gui."
            }
        }
    }
}
