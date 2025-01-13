package mill.kotlinlib.android

import mill.{Agg, T, Task}
import mill.api.PathRef
import mill.kotlinlib.{DepSyntax, KotlinModule}
import mill.javalib.android.AndroidAppModule

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
trait AndroidAppKotlinModule extends AndroidAppModule with KotlinModule { outer =>

  override def sources: T[Seq[PathRef]] =
    super.sources() :+ PathRef(millSourcePath / "src/main/kotlin")

  override def kotlincOptions = super.kotlincOptions() ++ {
    if (androidEnableCompose()) {
      Seq(
        // TODO expose Compose configuration options
        // https://kotlinlang.org/docs/compose-compiler-options.html possible options
        s"-Xplugin=${composeProcessor().path}"
      )
    } else Seq.empty
  }

  /**
   * Enable Jetpack Compose support in the module. Default is `false`.
   */
  def androidEnableCompose: T[Boolean] = false

  private def composeProcessor = Task {
    // cut-off usages for Kotlin 1.x, because of the need to maintain the table of
    // Compose compiler version -> Kotlin version
    if (kotlinVersion().startsWith("1"))
      throw new IllegalStateException("Compose can be used only with Kotlin version 2 or newer.")
    defaultResolver().resolveDeps(
      Agg(
        ivy"org.jetbrains.kotlin:kotlin-compose-compiler-plugin:${kotlinVersion()}"
      )
    ).head
  }

  trait AndroidAppKotlinTests extends AndroidAppTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super.sources() :+ PathRef(outer.millSourcePath / "src/test/kotlin")
  }

  trait AndroidAppKotlinInstrumentedTests extends AndroidAppKotlinModule
      with AndroidAppInstrumentedTests {

    override final def kotlinVersion = outer.kotlinVersion
    override final def androidSdkModule = outer.androidSdkModule

    override def sources: T[Seq[PathRef]] =
      super.sources() :+ PathRef(outer.millSourcePath / "src/androidTest/kotlin")
  }
}
