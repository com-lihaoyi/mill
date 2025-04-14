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
