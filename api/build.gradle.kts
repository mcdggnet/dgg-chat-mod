description = "The identity SPI. Platform-free: no Minecraft, no NeoForge, unit-testable."

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 omits the launcher from the test runtime classpath.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "dgg-chat-api"
            pom {
                name = "DGG Chat API"
                description = "SPI for supplying Destiny.gg identities to dgg-chat-mod."
            }
        }
    }
}
