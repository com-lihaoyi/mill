package mill.androidlib.hilt

import mill.androidlib.AndroidKotlinModule
import mill.api.{ModuleRef, PathRef}
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.ksp.KspBaseModule
import mill.javalib.Dep
import mill.javalib.api.CompilationResult
import mill.{T, Task}

/**
 * Trait for mixing in AndroidAppKotlinModule to
 * support the Hilt Dependency Injection framework for Android.
 *
 * It prepends compilation steps by using Kotlin Symbol Processing
 * for pre-processing the Hilt annotations and generating the necessary classes for Hilt to work, then
 * compiles all the sources together with a Java pre-processor step and finally a transform ASM step
 * to achieve the compile time dependency injection!
 *
 * Usage:
 * ```
 *
 * object app extends KspModule with AndroidHiltSupport { ... }
 *
 * // or
 *
 * object app extends Ksp2Module with AndroidHiltSupport { ... }
 * ```
 */
@mill.api.experimental
trait AndroidHiltSupport extends KspBaseModule with AndroidKotlinModule {

  def androidHiltProcessorPath: T[Seq[PathRef]] = Task {
    kspDependencyResolver().classpath(
      kotlinSymbolProcessors().flatMap {
        dep =>
          if (dep.dep.module.name.value == "hilt-android-compiler")
            Seq(
              dep,
              mvn"com.google.dagger:hilt-compiler:${dep.version}"
            )
          else
            Seq(dep)
      }
    )
  }

  override def kspProcessorOptions: T[Map[String, String]] = Task {
    super.kspProcessorOptions() ++ Map(
      "dagger.fastInit" -> "enabled",
      "dagger.hilt.android.internal.disableAndroidSuperclassValidation" -> "true",
      "dagger.hilt.android.internal.projectType" -> "APP",
      "dagger.hilt.internal.useAggregatingRootProcessor" -> "true"
    )
  }

  def androidHiltModule: ModuleRef[AndroidHiltTransform] = ModuleRef(AndroidHiltTransform)

  /** Compile and then transform asm for Hilt DI */
  override def compile: T[CompilationResult] = Task {
    val transformClasses = androidHiltModule().androidHiltTransformAsm(
      Task.Anon {
        super.compile().classes
      }
    )()

    val analysisFile = Task.dest / "kotlin.analysis.dummy"
    os.write(target = analysisFile, data = "", createFolders = true)

    CompilationResult(analysisFile, transformClasses)
  }

  def hiltProcessorClasspath: T[Seq[PathRef]] = Task {
    kotlinSymbolProcessorsResolved() ++ kspClasspath()
  }

  override def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    kspDependencyResolver().classpath(
      kotlinSymbolProcessors()
    )
  }

  override def androidProguard: T[PathRef] = Task {
    val inheritedProguardFile = super.androidProguard()

    val hiltContent: String =
      """
        |# Keep any class annotated with @HiltAndroidApp, @AndroidEntryPoint, etc.
        |-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
        |-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
        |""".stripMargin

    val globalProguard = Task.dest / "global-proguard.pro"
    os.write(globalProguard, os.read(inheritedProguardFile.path))
    os.write.append(globalProguard, hiltContent)
    os.write.append(globalProguard, androidProviderProguardConfigRules().mkString("\n"))
    PathRef(globalProguard)
  }

}
