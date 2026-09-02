import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()
    sourceSets {
        named("jvmMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":common"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.example.hellokmp.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "hello-kmp"
            packageVersion = "1.0.0"

            windows {
                menu = true
                upgradeUuid = "86CEAEC8-ABF0-4BB1-9A6B-BBC146697C73"
            }
        }

        buildTypes.release {
            proguard {
                configurationFiles.from(project.file("compose-desktop.pro"))
            }
        }
    }
}
