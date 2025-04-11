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
    val compilationResult: CompilationResult = super.compile()
    val transformed = Task.dest / "transformed/classes"
    val transformedClasses = androidHiltTransformAsm(
      androidHiltModule().toolsClasspath(),
      compilationResult.classes,
      PathRef(transformed)
    )
    CompilationResult(
      compilationResult.analysisFile,
      transformedClasses
    )
  }

  /**
   * Transforms the Kotlin classes with Hilt dependency injection context
   * and returns the new path of the kotlin compiled classpath. This uses
   * the [mill.main.android.hilt.AndroidHiltTransformAsm] that uses
   * the hilt gradle plugin and the android build tools
   */
  private def androidHiltTransformAsm(
      toolsClasspath: Seq[PathRef],
      compiledClasses: PathRef,
      destination: PathRef
  )(implicit ctx: mill.define.TaskCtx): PathRef = {

    val kotlinCompiledClassesDir = compiledClasses.path

    val transformedClasses = destination.path

    os.makeDir.all(transformedClasses)
    val mainClass = "mill.main.android.hilt.AndroidHiltTransformAsm"

    val classPath = toolsClasspath.map(_.path)

    mill.util.Jvm.callProcess(
      mainClass = mainClass,
      classPath = classPath,
      mainArgs = Seq(kotlinCompiledClassesDir.toString, transformedClasses.toString)
    )

    PathRef(transformedClasses)

  }

  def hiltProcessorClasspath: T[Seq[PathRef]] = Task {
    kspApClasspath() ++ kspClasspath()
  }

}

object AndroidHiltTransform extends ExternalModule with JvmWorkerModule {

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  def toolsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-main-androidhilt")
      )
    )
  }

  override lazy val millDiscover: Discover = Discover[this.type]
}
