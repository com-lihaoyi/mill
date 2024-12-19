package mill.javalib.android

import mill.{Agg, T, Target, Task}
import mill.api.PathRef
import mill.define.ModuleRef
import mill.scalalib.JavaModule
import os.{CommandResult, Path}
import mill.*

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Java sources,
 * integrating both Android-specific tasks and generic Java tasks by extending the
 * [[JavaModule]] (for standard Java tasks)
 * and [[AndroidAppModule]] (for Android Application Workflow Process).
 *
 * It provides a structured way to handle various steps in the Android app build process,
 * including compiling Java sources, creating DEX files, generating resources, packaging
 * APKs, optimizing, and signing APKs.
 *
 * [[https://developer.android.com/studio Android Studio Documentation]]
 */

@mill.api.experimental
trait AndroidAppJavaModule extends AndroidAppModule with JavaModule {

  final def root: Path = super.millSourcePath
  private def src: Path = root / "src"
  override def millSourcePath: Path = src / "main"

  override def sources: T[Seq[PathRef]] = Task.Sources(millSourcePath)

  override def compileClasspath: T[Agg[PathRef]] = super.compileClasspath().filter(bannedModules)

  private def bannedModules(classpath: PathRef): Boolean =
    !classpath.path.last.contains("-jvm")

  trait AndroidAppJavaTests extends JavaTests {
    def testPath: T[Seq[PathRef]] = Task.Sources(src / "test")

    override def allSources: T[Seq[PathRef]] = Task {
      super.allSources() ++ testPath()
    }
  }

  private def sdk = androidSdkModule

  trait AndroidAppJavaIntegrationTests extends AndroidAppJavaModule with AndroidTestModule {
    override def millSourcePath: Path = src / "main"

    def androidTestPath: Path = src / "androidTest"

    override def sources: T[Seq[PathRef]] = Task.Sources(millSourcePath, androidTestPath)

    def instrumentationPackage: String

    def testFramework: T[String]

    override def install: Target[CommandResult] = Task {
      os.call(
        (androidSdkModule().adbPath().path, "install", "-r", androidInstantApk().path)
      )
    }

    def test: T[Vector[String]] = Task {
      install()
      val instrumentOutput = os.call(
        (androidSdkModule().adbPath().path, "shell", "am", "instrument", "-w", "-m", s"${instrumentationPackage}/${testFramework()}")
      )

      instrumentOutput.out.lines()
    }

    override def androidSdkModule: ModuleRef[AndroidSdkModule] = sdk

    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidInstantApk: T[PathRef] = androidDebugApk

  }
}
