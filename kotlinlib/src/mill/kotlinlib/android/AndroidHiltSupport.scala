package mill.kotlinlib.android

import coursier.Repository
import mill.api.{PathRef, Result}
import mill.define.{Discover, ExternalModule, ModuleRef}
import mill.javalib.android.AndroidSdkModule
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.android.AndroidHiltSupport.HiltGeneratedClasses
import mill.kotlinlib.ksp.KspModule
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.scalalib.{Dep, JvmWorkerModule}
import mill.{T, Task}
import os.Path

import java.io.File

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

  /**
   * With Hilt support, sources are compiled as part of the
   * Hilt compilation flow which involves an asm transform step
   * on the compiled sources, so we don't want to recompile them.
   */
  override def allSources: T[Seq[PathRef]] = generatedSources()

  /**
   * In the case of hilt, we are not adding any generated
   * KSP files in generated sources - yet
   */
  override def generatedSources: T[Seq[PathRef]] =
    super[AndroidAppKotlinModule].generatedSources()

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

  def androidJavaCompileKSPGeneratedSources: T[HiltGeneratedClasses] = Task {
    val directory = Task.dest / "ap_generated/out"

    os.makeDir.all(directory)
    val generatedKSPSources = generatedSourcesWithKSP()

    val generatedPath = Task.dest / "generated"
    os.makeDir(generatedPath)

    val javaSources = generatedPath / "java"

    os.copy(generatedKSPSources.java.path, javaSources)

    val daggerSourcesOrigin = generatedPath / "java/dagger"
    val hiltAggregatedDepsSourcesOrigin = generatedPath / "java/hilt_aggregated_deps"

    val hiltSources = generatedPath / "hilt"
    os.makeDir(hiltSources)

    val daggerSources = hiltSources / "dagger"
    val hiltAggregatedDepsSources = hiltSources / "hilt_aggregated_deps"

    os.move(daggerSourcesOrigin, daggerSources)
    os.move(hiltAggregatedDepsSourcesOrigin, hiltAggregatedDepsSources)

    val javaGeneratedSources = Seq(daggerSources, hiltAggregatedDepsSources, javaSources)
      .flatMap(os.walk(_))
      .filter(os.exists)
      .filter(_.ext == "java")

    val kotlinClasses = Task.dest / "kotlin"

    os.makeDir.all(kotlinClasses)

    val kotlinClasspath = kspClasspath() :+ androidProcessResources()

    val kotlinSourceFiles: Seq[Path] =
      kspSources().map(_.path).filter(os.exists).flatMap(os.walk(_))
        .filter(path => Seq("kt", "kts").contains(path.ext.toLowerCase()))

    val compileWithKotlin = Seq(
      "-d",
      kotlinClasses.toString,
      "-classpath",
      kotlinClasspath.map(_.path).mkString(File.pathSeparator)
    ) ++ mandatoryKotlincOptions() ++ kotlinSourceFiles.map(_.toString) ++
      javaGeneratedSources.map(_.toString) ++
      Seq(daggerSources.toString, hiltAggregatedDepsSources.toString)

    Task.log.info(s"Compiling kotlin classes ${compileWithKotlin.mkString(" ")}")

    kotlinWorkerTask().compile(KotlinWorkerTarget.Jvm, compileWithKotlin)

    val kspJavacOptions = Seq(
      "-XDstringConcat=inline",
      "-Adagger.fastInit=enabled",
      "-Adagger.hilt.internal.useAggregatingRootProcessor=true",
      "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
      "-g",
      "-XDuseUnsharedTable=true",
      "-proc:none",
      "-parameters",
      "-s",
      directory.toString
    )

    val compileCp =
      kspClasspath().map(_.path) ++ Seq(androidProcessResources().path, kotlinClasses)

    val worker = jvmWorker().worker()

    Task.log.info(
      s"Compiling ${javaGeneratedSources.size} Java generated sources to ${directory} ..."
    )

    val compilation = worker.compileJava(
      upstreamCompileOutput = Seq.empty,
      sources = javaGeneratedSources,
      compileClasspath = compileCp,
      javacOptions = kspJavacOptions,
      reporter = T.ctx().reporter(hashCode()),
      reportCachedProblems = zincReportCachedProblems(),
      incrementalCompilation = true
    ).get

    val hiltAggregatedDepsCompiledOrigin = compilation.classes.path / "hilt_aggregated_deps"
    val daggerCompiledOrigin = compilation.classes.path / "dagger"
    val hiltDir = Task.dest / "hilt"
    os.makeDir(hiltDir)

    val hiltAggregatedDepsCompiled = hiltDir / "hilt_aggregated_deps"
    val daggerCompiled = hiltDir / "dagger"

    os.move(from = hiltAggregatedDepsCompiledOrigin, hiltAggregatedDepsCompiled)
    os.move(from = daggerCompiledOrigin, daggerCompiled)

    HiltGeneratedClasses(
      apGenerated = PathRef(directory),
      javaSources = PathRef(javaSources),
      javaCompiled = compilation.classes,
      kotlinCompiled = PathRef(kotlinClasses),
      hiltAggregatedDepsSources = PathRef(hiltAggregatedDepsSources),
      hiltAggregatedDepsCompiled = PathRef(hiltAggregatedDepsCompiled),
      daggerSources = PathRef(daggerSources),
      daggerCompiled = PathRef(daggerCompiled)
    )
  }

  def androidHiltGeneratedClasses: T[Seq[PathRef]] = Task {
    val directory = Task.dest / "component_classes" / "out"
    os.makeDir.all(directory)

    val hiltGeneratedClasses: HiltGeneratedClasses = androidJavaCompileKSPGeneratedSources()

    val hiltJavacOptions = Seq(
      "-processorpath",
      androidHiltProcessorPath().map(_.path.toString).mkString(File.pathSeparator),
      "-XDstringConcat=inline",
      "-Adagger.fastInit=enabled",
      "-Adagger.hilt.internal.useAggregatingRootProcessor=false",
      "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
      "-XDuseUnsharedTable=true",
      "-parameters",
      "-s",
      directory.toString
    )

    val compileCp = hiltProcessorClasspath().map(_.path) ++
      Seq(
        hiltGeneratedClasses.kotlinCompiled.path,
        hiltGeneratedClasses.javaCompiled.path,
        hiltGeneratedClasses.daggerCompiled.path / os.up,
        hiltGeneratedClasses.javaSources.path
      )

    val worker = jvmWorker().worker()

    val classes = Task.dest / "classes"
    os.makeDir.all(classes)

    val processedRoots = os.walk(
      hiltGeneratedClasses.daggerSources.path / "hilt/internal/processedrootsentinel"
    ).filter(_.ext == "java")

    val componentTreeDeps = os.walk(hiltGeneratedClasses.javaSources.path)
      .filter(_.toString.endsWith("_ComponentTreeDeps.java"))

    val sourcesToCompile = processedRoots ++ componentTreeDeps

    T.log.info(s"Compiling ${sourcesToCompile.mkString(" ")} java sources to ${classes}")

    val result = worker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput(),
      sources = sourcesToCompile,
      compileClasspath = compileCp,
      javacOptions = hiltJavacOptions,
      reporter = T.ctx().reporter(hashCode()),
      reportCachedProblems = zincReportCachedProblems(),
      incrementalCompilation = true
    )

    val mergedClasses = Task.dest / "merged_classes"
    os.makeDir(mergedClasses)

    def merge(path: os.Path, mergedClasses: os.Path = mergedClasses): Unit =
      os.copy(
        path,
        mergedClasses,
        mergeFolders = true,
        replaceExisting = true,
        createFolders = true
      )

    val tranformedAsmClasses = androidHiltTransformAsm()

    merge(tranformedAsmClasses.kotlinCompiled.path)

    merge(
      hiltGeneratedClasses.hiltAggregatedDepsCompiled.path,
      mergedClasses / "hilt_aggregated_deps"
    )

    merge(hiltGeneratedClasses.daggerSources.path, mergedClasses / "dagger")

    merge(result.get.classes.path)

    merge(hiltGeneratedClasses.javaCompiled.path)

    Seq(PathRef(mergedClasses))

  }

  def androidHiltModule = ModuleRef(AndroidHiltTransform)

  /**
   * Transforms the Kotlin classes with Hilt dependency injection context
   *  and returns the new path of the kotlin compiled classpath. This uses
   *  the [mill.main.android.hilt.AndroidHiltTransformAsm] that uses
   *  the hilt gradle plugin and the android build tools
   */
  def androidHiltTransformAsm: T[HiltGeneratedClasses] = Task {
    val hiltGeneratedSources = androidJavaCompileKSPGeneratedSources()
    val kotlinCompiledClassesDir = hiltGeneratedSources.kotlinCompiled.path

    val transformedClasses = Task.dest / "transformed_asm_classes"

    os.makeDir.all(transformedClasses)
    val mainClass = "mill.main.android.hilt.AndroidHiltTransformAsm"

    val classPath = androidHiltModule().toolsClasspath().map(_.path)

    T.log.info(s"Tools classpath ${classPath}")

    mill.util.Jvm.callProcess(
      mainClass = mainClass,
      classPath = classPath,
      mainArgs = Seq(kotlinCompiledClassesDir.toString, transformedClasses.toString)
    )

    hiltGeneratedSources.copy(
      kotlinCompiled = PathRef(transformedClasses)
    )

  }

  override def androidGeneratedCompiledClasses: T[Seq[PathRef]] = Task {
    androidHiltGeneratedClasses()
  }

  def hiltProcessorClasspath: T[Seq[PathRef]] = Task {
    kspApClasspath() ++ kspClasspath()
  }

  override def compileClasspath: T[Seq[PathRef]] = Task {
    super.compileClasspath() ++ androidGeneratedCompiledClasses()
  }

}

object AndroidHiltSupport {
  case class HiltGeneratedClasses(
      apGenerated: PathRef,
      javaSources: PathRef,
      kotlinCompiled: PathRef,
      javaCompiled: PathRef,
      daggerSources: PathRef,
      daggerCompiled: PathRef,
      hiltAggregatedDepsSources: PathRef,
      hiltAggregatedDepsCompiled: PathRef
  ) {
    def classpath = Seq(kotlinCompiled, javaCompiled)
  }

  object HiltGeneratedClasses {
    implicit def resultRW: upickle.default.ReadWriter[HiltGeneratedClasses] =
      upickle.default.macroRW
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
