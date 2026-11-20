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

    // The llama.cpp backend resolves native libraries from applicationInfo.nativeLibraryDir.
    // Like Harnex's Host and device runner, extract JNI libraries into that directory.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.daniele21.localllm:embedded-android:${embeddedSdkVersion.get()}")
    testImplementation("junit:junit:4.13.2")
}
