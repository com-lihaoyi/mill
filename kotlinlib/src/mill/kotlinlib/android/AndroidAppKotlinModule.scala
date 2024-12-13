package mill.kotlinlib.android

import mill.T
import mill.api.PathRef
import mill.define.{ModuleRef, Task}
import mill.kotlinlib.KotlinModule
import mill.javalib.android.{AndroidAppModule, AndroidSdkModule, AndroidTestModule}
import os.{Path, home}

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

  final def root: Path = super.millSourcePath
  private def src: Path = root / "src"
  override def millSourcePath: Path = src / "main"

  trait AndroidAppKotlinTests extends KotlinTests {
    def testPath: T[Seq[PathRef]] = Task.Sources(src / "test")

    override def allSources: T[Seq[PathRef]] = Task { super.allSources() ++ testPath() }
  }

  private def sdk = androidSdkModule
  trait AndroidAppKotlinIntegrationTests extends AndroidAppModule with AndroidTestModule {
    override def millSourcePath: Path = src / "main"
    def androidTestPath: T[Seq[PathRef]] = Task.Sources(src / "androidTest")

    override def androidSdkModule: ModuleRef[AndroidSdkModule] = sdk

    override def allSources: T[Seq[PathRef]] = Task { super.allSources() ++ androidTestPath() }

    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidInstantApk: T[PathRef] = super.androidDebugApk
  }
}
