plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.daniele21.localllm.embedded"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
        htmlReport = true
        sarifReport = true
    }
}

dependencies {
    api(project(":core:contracts"))
    implementation(project(":core:runtime-core"))
    implementation(project(":models:model-profile"))
    implementation(project(":models:model-store"))
    implementation(project(":models:model-catalog"))
    api(project(":models:model-download"))
    api(project(":models:model-install"))
    implementation(project(":transports:in-process"))
    implementation(project(":backends:llama-cpp"))

    testImplementation(libs.junit4)
}
