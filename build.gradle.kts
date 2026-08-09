// Shared Java setup. Per-platform plugins are applied in each module, so building
// :api never triggers ModDevGradle's Minecraft download and decompile.
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "net.mcdgg"
    // -PmodVersion=1.2.3 overrides, which is how the release workflow stamps a tag.
    version = providers.gradleProperty("modVersion").getOrElse("0.0.0-SNAPSHOT")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        // Minecraft 1.21.1 runs on Java 21 bytecode. Anything newer will not load.
        options.release = 21
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/mcdggnet/dgg-chat-mod")
                credentials {
                    // Supplied by the workflow. Never hardcode either of these.
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
