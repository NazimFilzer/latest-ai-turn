import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "dev.latestaiturn"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Build against a locally installed IntelliJ IDEA (no multi-GB download).
// Override with -PideaPath=/path/to/IntelliJ\ IDEA.app
val ideaPath = providers.gradleProperty("ideaPath").orElse("/Applications/IntelliJ IDEA.app")

dependencies {
    intellijPlatform {
        local(ideaPath)
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.latestaiturn"
        name = "Latest AI Turn"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
        }
    }
}
