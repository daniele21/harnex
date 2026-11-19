import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

group = "io.github.daniele21.localllm"
version = "0.1.0-SNAPSHOT"

val detektCli by configurations.creating

dependencies {
    detektCli(libs.detekt.cli)
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

/**
 * A second, explicit distribution mode for the existing production Android modules.
 * Use normal transitive Maven metadata instead of a fat AAR or copying native sources.
 * Keep the existing Consumer SDK publication and Binder dependencies unchanged.
 */
val embeddedPublishingModules =
    setOf(
        ":core:backend-spi",
        ":core:runtime-core",
        ":models:model-profile",
        ":models:model-store",
        ":models:model-catalog",
        ":models:model-download",
        ":models:model-install",
        ":backends:llama-cpp",
        // Diagnostic: isolate publication-induced task cycles in observability.
        ":transports:in-process",
        ":integrations:android-embedded-sdk",
    )
val embeddedSdkVersion =
    providers.gradleProperty("embeddedSdkVersion")
        .orElse(providers.gradleProperty("consumerSdkVersion"))
        .orElse("0.1.0-SNAPSHOT")

subprojects {
    if (path in embeddedPublishingModules) {
        group = "io.github.daniele21.localllm"
        version = embeddedSdkVersion.get()
        plugins.withId("com.android.library") {
            // Existing Consumer artifacts apply Android before maven-publish.
            // Preserve that plugin ordering for the new publication graph.
            pluginManager.apply("maven-publish")
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                    }
                }
            }
            extensions.configure<org.gradle.api.publish.PublishingExtension> {
                repositories {
                    maven {
                        name = "embeddedSdk"
                        url = uri(rootProject.layout.buildDirectory.dir("embedded-sdk-repository"))
                    }
                }
                publications {
                    register<org.gradle.api.publish.maven.MavenPublication>("embeddedRelease") {
                        groupId = project.group.toString()
                        artifactId = if (project.path == ":integrations:android-embedded-sdk") "embedded-android" else project.name
                        version = project.version.toString()
                        afterEvaluate { from(components["release"]) }
                    }
                }
            }
        }
    }
}

// core-contracts already has a Consumer publication. Reuse it with the same version
// in the embedded staging repository rather than publishing duplicate coordinates.
project(":core:contracts") {
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories {
                maven {
                    name = "embeddedSdk"
                    url = uri(rootProject.layout.buildDirectory.dir("embedded-sdk-repository"))
                }
            }
        }
    }
}

configure<SpotlessExtension> {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "third_party/**")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "third_party/**")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target(
            ".editorconfig",
            ".gitignore",
            "**/*.md",
            "**/*.toml",
            "**/*.yml",
            "**/*.yaml",
            "**/*.xml",
        )
        targetExclude("**/build/**", "third_party/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val detektReportDirectory = layout.buildDirectory.dir("reports/detekt")
val detektInputs = providers.provider {
    fileTree(rootDir) {
        include("**/src/**/*.kt")
        exclude("**/build/**", "third_party/**")
    }.files
        .map { file ->
            generateSequence(file.parentFile) { parent -> parent.parentFile }
                .firstOrNull { directory -> directory.name == "kotlin" }
                ?: generateSequence(file.parentFile) { parent -> parent.parentFile }
                    .first { directory -> directory.name == "src" }
        }
        .distinct()
        .sortedBy { it.absolutePath }
        .joinToString(",") { it.absolutePath }
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs Detekt CLI without coupling it to the Android Gradle Plugin."
    notCompatibleWithConfigurationCache("Detekt CLI inputs are assembled from the repository source tree.")
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")

    doFirst {
        detektReportDirectory.get().asFile.mkdirs()
    }

    args(
        "--input",
        detektInputs.get(),
        "--config",
        rootProject.file("config/detekt/detekt.yml").absolutePath,
        "--build-upon-default-config",
        "--report",
        "html:${detektReportDirectory.get().file("detekt.html").asFile.absolutePath}",
        "--report",
        "sarif:${detektReportDirectory.get().file("detekt.sarif").asFile.absolutePath}",
    )
}

tasks.register("verifyNoModelArtifacts") {
    group = "verification"
    description = "Fails when project-owned GGUF/GGML model binaries are present in the repository source tree."
    notCompatibleWithConfigurationCache("The guard intentionally scans the live repository tree.")

    doLast {
        val forbiddenFiles = fileTree(rootDir) {
            include("**/*.gguf", "**/*.ggml")
            exclude("**/build/**", ".gradle/**", "third_party/**")
        }.files

        check(forbiddenFiles.isEmpty()) {
            "Model binaries must not be committed or bundled from the repository: " +
                forbiddenFiles.joinToString { it.relativeTo(rootDir).path }
        }
    }
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs repository formatting, static analysis and model-artifact guards."
    dependsOn("spotlessCheck", "detekt", "verifyNoModelArtifacts")
}
