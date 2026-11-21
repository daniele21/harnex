pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val embeddedSdkRepositoryUrl =
    providers.gradleProperty("embeddedSdkRepositoryUrl")
        .orElse("../../build/embedded-sdk-repository")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(embeddedSdkRepositoryUrl.get()) }
        google()
        mavenCentral()
    }
}

rootProject.name = "external-embedded-android"
include(":app")
