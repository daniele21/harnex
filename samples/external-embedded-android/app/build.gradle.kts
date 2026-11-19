plugins {
    id("com.android.application")
}

val embeddedSdkVersion = providers.gradleProperty("embeddedSdkVersion").orElse("0.1.0-SNAPSHOT")

android {
    namespace = "io.github.daniele21.localllm.externalembedded"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.daniele21.localllm.embedded.smoke"
        minSdk = 26
        targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.github.daniele21.localllm:embedded-android:${embeddedSdkVersion.get()}")
}
