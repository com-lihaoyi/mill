package mill.androidlib.hilt

import mill.androidlib.AndroidKotlinModule
import mill.api.{ModuleRef, PathRef}
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.ksp.KspModule
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
 */
@mill.api.experimental
trait AndroidHiltSupport extends KspModule with AndroidKotlinModule {

  override def kspClasspath: T[Seq[PathRef]] =
    super.kspClasspath()

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

  override def kspPluginParameters: T[Seq[String]] = Task {
    super.kspPluginParameters() ++
      Seq(
        s"apoption=dagger.fastInit=enabled",
        s"apoption=dagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
        s"apoption=dagger.hilt.android.internal.projectType=APP",
        s"apoption=dagger.hilt.internal.useAggregatingRootProcessor=true"
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
    kspApClasspath() ++ kspClasspath()
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
        |# Hilt generated Application, Activities, Fragments
        |-keep class dagger.hilt.internal.GeneratedApplication { *; }
        |-keep class * extends android.app.Application
        |-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory
        |-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
        |-keep class dagger.hilt.android.internal.managers.** { *; }
        |-keep class dagger.hilt.android.components.** { *; }
        |-keep class dagger.hilt.internal.components.** { *; }
        |-keep class dagger.hilt.internal.GeneratedComponent { *; }
        |-keep class **_HiltModules_* { *; }
        |
        |# Keep any class annotated with @HiltAndroidApp, @AndroidEntryPoint, etc.
        |-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
        |-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
        |-keep @dagger.hilt.InstallIn class * { *; }
        |-keep @dagger.hilt.components.SingletonComponent class * { *; }
        |
        |# Keep any Hilt-generated code related to entry points and components
        |-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$HiltViewModelFactoryModule { *; }
        |""".stripMargin

    val globalProguard = Task.dest / "global-proguard.pro"
    os.write(globalProguard, os.read(inheritedProguardFile.path))
    os.write.append(globalProguard, hiltContent)
    os.write.append(globalProguard, providerProguardConfigRules().mkString("\n"))
    PathRef(globalProguard)
  }

}
