plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.helloworld.gradle8"
    compileSdk = 34
    // Pinned explicitly: AGP 8.7.3's own default (34.0.0) ships an aapt2 binary 
    // that fails parsing multi-entry "-R @argfile" resource lists, fixed in later build-tools releases.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.helloworld.gradle8"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    testImplementation(libs.junit)
}
