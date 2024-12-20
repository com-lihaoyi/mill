package mill.kotlinlib.android

import mill.api.PathRef
import mill.define.ModuleRef
import mill.javalib.android.{AndroidAppModule, AndroidSdkModule, AndroidTestModule}
import mill.kotlinlib.KotlinModule
import mill.{Agg, T, Target, Task}
import mill.*
import os.{CommandResult, Path}

import java.nio.charset.StandardCharsets

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Kotlin sources,
 * integrating both Android-specific tasks and generic Kotlin tasks by extending the
 * [[KotlinModule]] (for standard Kotlin tasks)
 * and [[AndroidAppModule]] (for Android Application Workflow Process).
 *
 * It provides a structured way to handle various steps in the Android app build process,
 * including compiling Kotlin sources, creating DEX files, generating resources, packaging
 * APKs, optimizing, and signing APKs.
 *
 * [[https://developer.android.com/studio Android Studio Documentation]]
 */
@mill.api.experimental
trait AndroidAppKotlinModule extends AndroidAppModule with KotlinModule {

  trait AndroidAppKotlinTests extends AndroidAppTests with KotlinTests

  trait AndroidAppKotlinIntegrationTests extends AndroidAppKotlinModule with AndroidAppIntegrationTests
}


