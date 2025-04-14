package mill.kotlinlib.android

import coursier.Repository
import mill.define.{Discover, ExternalModule, ModuleRef, PathRef}
import mill.javalib.android.AndroidSdkModule
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.ksp.KspModule
import mill.scalalib.api.CompilationResult
import mill.scalalib.{Dep, JvmWorkerModule}
import mill.{T, Task}
import os.Path

/**
 * Trait for mixing in AndroidAppKotlinModule to
 * support the Hilt Dependency Injection framework for Android.
 *
 * It prepends compilation steps by using Kotlin Symbol Processing
 * for pre-processing the Hilt annotations and generating the necessary classes for Hilt to work, then
 * compiles all the sources together with a Java pre-processor step and finally a transform ASM step
 * to achieve the compile time dependency injection!
 */
@mill.api.experimental
trait AndroidHiltSupport extends KspModule with AndroidAppKotlinModule {

  override def kspClasspath: T[Seq[PathRef]] =
    Seq(androidProcessResources()) ++ super.kspClasspath()

  def androidHiltProcessorPath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors().flatMap {
        dep =>
          if (dep.dep.module.name.value == "hilt-android-compiler")
            Seq(
              dep,
              ivy"com.google.dagger:hilt-compiler:${dep.version}"
            )
          else
            Seq(dep)
      }
    )
  }

  override def kspPluginParameters: T[Seq[String]] = Task {
    super.kspPluginParameters() ++
      Seq(
        s"apoption=dagger.fastInit=enabled",
        s"apoption=dagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
        s"apoption=dagger.hilt.android.internal.projectType=APP",
        s"apoption=dagger.hilt.internal.useAggregatingRootProcessor=true"
      )
  }

  def androidHiltModule = ModuleRef(AndroidHiltTransform)

  /** Compile and then transform asm for Hilt DI */
  override def compile: T[CompilationResult] = Task {
    val transformClasses = androidHiltModule().androidHiltTransformAsm(
      Task.Anon {
        super.compile().classes
      }
    )()
    CompilationResult(super.compile().analysisFile, transformClasses)
  }

  def hiltProcessorClasspath: T[Seq[PathRef]] = Task {
    kspApClasspath() ++ kspClasspath()
  }

}

trait AndroidHiltTransform extends ExternalModule with JvmWorkerModule {

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  /**
   * Transforms the Kotlin classes with Hilt dependency injection context
   * and returns the new path of the kotlin compiled classpath. This uses
   * the [[mill.kotlinlib.android.hilt.AndroidHiltTransformAsm]] that uses
   * the hilt gradle plugin and the android build tools.
   */
  def androidHiltTransformAsm(
      compiledClasses: Task[PathRef]
  ): Task[PathRef] = Task.Anon {

    val kotlinCompiledClassesDir = compiledClasses().path
    val transformedClasses = Task.dest / "transformed/classes"

    os.makeDir.all(transformedClasses)

    val mainClass = "mill.kotlinlib.android.hilt.AndroidHiltTransformAsm"

    val classPath: Seq[os.Path] = defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-kotlinlib-androidhilt")
      )
    ).map(_.path)

    mill.util.Jvm.callProcess(
      mainClass = mainClass,
      classPath = classPath,
      mainArgs = Seq(kotlinCompiledClassesDir.toString, transformedClasses.toString)
    )

    PathRef(transformedClasses)

  }

  override lazy val millDiscover: Discover = Discover[this.type]
}

object AndroidHiltTransform extends AndroidHiltTransform
