package mill.kotlinlib.android

import mill.T
import mill.api.PathRef
import mill.javalib.android.AndroidAppModule
import mill.kotlinlib.KotlinModule

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

  private def ktVersion = kotlinVersion
  private def sdkModule = androidSdkModule

  override def sources: T[Seq[PathRef]] =
    super.sources() :+ PathRef(millSourcePath / "src/main/kotlin")

  private val parent = this

  trait AndroidAppKotlinTests extends AndroidAppTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super.sources() :+ PathRef(parent.millSourcePath / "src/test/kotlin")
  }

  trait AndroidAppKotlinIntegrationTests extends AndroidAppKotlinModule
      with AndroidAppIntegrationTests {

    override final def kotlinVersion = ktVersion
    override final def androidSdkModule = sdkModule

    override def sources: T[Seq[PathRef]] =
      super.sources() :+ PathRef(parent.millSourcePath / "src/androidTest/kotlin")

  }
}
