package mill.kotlinlib.android

import mill.T
import mill.api.PathRef
import mill.define.Task
import mill.kotlinlib.KotlinModule
import mill.javalib.android.AndroidAppModule
import os.Path

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

  private def src: Path = super.millSourcePath / "src"
  override def millSourcePath: Path = src / "main"


  trait AndroidAppKotlinTests extends KotlinTests {
    def testPath = Task.Sources(src / "test")

    override def allSources: T[Seq[PathRef]] = Task { super.allSources() ++ testPath() }
  }

  trait AndroidAppKotlinIntegrationTests extends  KotlinTests {
  }
}
