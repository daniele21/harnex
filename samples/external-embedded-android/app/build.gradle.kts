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
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Harnex's pinned llama.cpp requires extracted JNI libraries.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.daniele21.localllm:embedded-android:${embeddedSdkVersion.get()}")
    implementation("io.github.daniele21.localllm:embedded-whisper-android:${embeddedSdkVersion.get()}")
    testImplementation("junit:junit:4.13.2")
}
