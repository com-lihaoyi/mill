plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.helloworld.app"
    compileSdk {
        version = release(35)
    }

    defaultConfig {
        applicationId = "com.helloworld.app"
        minSdk = 19
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("releaseKey.jks")
            storePassword = "MillBuildTool"
            keyAlias = "releaseKey"
            keyPassword = "MillBuildTool"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.junit)
}
