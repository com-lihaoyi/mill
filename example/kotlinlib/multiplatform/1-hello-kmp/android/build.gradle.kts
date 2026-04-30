plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

android {
    compileSdk = 35
    namespace = "com.example.hellokmp"

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        applicationId = "com.example.hellokmp"
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":common"))
}
