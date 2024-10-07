package mill.kotlinlib.android

import mill._
import mill.api.PathRef
import mill.define.ModuleRef
import mill.kotlinlib.KotlinModule
import mill.javalib.android.{AndroidSdkModule,AndroidAppModule}

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Kotlin sources,
 * integrating both Android-specific tasks and generic Kotlin tasks by extending the
 * [[AndroidSdkModule]] (for Android SDK interactions), [[KotlinModule]] (for standard Kotlin tasks)
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

  /**
   * Abstract method to provide access to the Android SDK configuration.
   *
   * This method must be implemented by the concrete class to specify the SDK paths.
   *
   * @return The Android SDK module that is used across the project.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

 
  /**
   * Packages the compiled Kotlin `.class` files into a JAR file using the D8 tool.
   *
   * The D8 compiler is used here to package and optimize the Kotlin bytecode into a format
   * suitable for Android (DEX). D8 converts the Kotlin `.class` files into a jar file which is
   * suitable for DEX (Dalvik Executable) format and is required for Android runtime.
   * 
   * This Redefines the parent androidJar Task for Kotlin Specific Compilation.
   *
   * For more details on the d8 tool, refer to:
   * [[https://developer.android.com/tools/d8 d8 Documentation]]
   */
  def androidJar: T[PathRef] = Task {
    val jarFile: os.Path = T.dest / "app.jar"

    os.call(
      Seq(
        androidSdkModule().d8Path().path.toString, // Call d8 tool
        "--output",
        jarFile.toString, // Output JAR file
        "--no-desugaring" // Disable desugaring
      ) ++ os.walk(compile().classes.path).filter(_.ext == "class").map(
        _.toString
      ) // Get Kotlin class files, this calls compile task from Kotlinlib
    )

    PathRef(jarFile)
  }

}
