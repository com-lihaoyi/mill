package mill.kotlinlib.kapt

import mill.*
import mill.api.{PathRef, Result}
import mill.api.daemon.internal.internal
import mill.javalib.UnresolvedPath
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.kotlinlib.{Dep, KotlinModule, KotlinWorkerManager}
import mill.kotlinlib.DepSyntax
import mainargs.Flag

import java.io.File

/**
 * Trait for KAPT (Kotlin annotation processing) modules.
 *
 * This runs KAPT as a separate generation phase (`stubsAndApt`) and then
 * delegates to the normal Kotlin/Java compilation pipeline from [[KotlinModule]].
 */
@mill.api.experimental
trait KaptModule extends KotlinModule { outer =>

  /**
   * Kotlin compiler plugin version used by KAPT.
   * Defaults to the same version as [[kotlinVersion]].
   */
  def kaptVersion: T[String] = kotlinVersion()

  /**
   * KAPT compiler plugin dependency.
   */
  def kaptPluginMvnDeps: T[Seq[Dep]] = Task {
    if (kotlinUseEmbeddableCompiler()) {
      Seq(mvn"org.jetbrains.kotlin:kotlin-annotation-processing-embeddable:${kaptVersion()}")
    } else {
      Seq(mvn"org.jetbrains.kotlin:kotlin-annotation-processing:${kaptVersion()}")
    }
  }

  /**
   * Annotation processor dependencies for KAPT.
   * Defaults to [[annotationProcessorsMvnDeps]] from [[mill.javalib.JavaModule]].
   */
  def kaptProcessorMvnDeps: T[Seq[Dep]] = annotationProcessorsMvnDeps()

  /**
   * Resolved annotation processor classpath used by KAPT.
   */
  def kaptProcessorClasspath: T[Seq[PathRef]] = Task {
    if (kaptProcessorMvnDeps().nonEmpty)
      defaultResolver().classpath(
        kaptProcessorMvnDeps(),
        boms = allBomDeps(),
        resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
      )
    else
      Seq.empty
  }

  /**
   * KAPT mode. Defaults to `stubsAndApt`, which is the supported mode on Kotlin 2.x.
   */
  def kaptAptMode: T[String] = Task { "stubsAndApt" }

  def kaptCorrectErrorTypes: T[Boolean] = Task { true }
  def kaptMapDiagnosticLocations: T[Boolean] = Task { true }

  override def kotlinUseEmbeddableCompiler: Task[Boolean] = Task { true }

  override def kotlincPluginMvnDeps: T[Seq[Dep]] = Task {
    super.kotlincPluginMvnDeps() ++ kaptPluginMvnDeps()
  }

  /**
   * Runs KAPT to generate Java sources and metadata before main compilation.
   */
  def generatedSourcesWithKapt: T[GeneratedKaptSources] = Task {
    val javaOutput = Task.dest / "generated" / "sources"
    val classesOutput = Task.dest / "generated" / "classes"
    val stubsOutput = Task.dest / "generated" / "stubs"
    val incrementalDataOutput = Task.dest / "generated" / "incrementalData"
    val phaseClassesOutput = Task.dest / "phase-compile-classes"

    Seq(javaOutput, classesOutput, stubsOutput, incrementalDataOutput, phaseClassesOutput)
      .foreach(os.makeDir.all)

    val sourceFiles =
      mill.javalib.Lib.findSourceFiles(sources(), Seq("kt", "kts")).filter(os.exists)
    if (sourceFiles.isEmpty || kaptProcessorClasspath().isEmpty) {
      GeneratedKaptSources(
        java = PathRef(javaOutput),
        classes = PathRef(classesOutput),
        stubs = PathRef(stubsOutput),
        incrementalData = PathRef(incrementalDataOutput)
      )
    } else {
      val compileCp = compileClasspath().map(_.path).filter(os.exists)
      val aptMode = kaptAptMode()
      if (kotlinLanguageVersion().startsWith("2.") && aptMode != "stubsAndApt") {
        throw new RuntimeException(
          s"KAPT with Kotlin ${kotlinLanguageVersion()} requires kaptAptMode=stubsAndApt."
        )
      }

      val apClasspathOpts = Seq(
        s"plugin:org.jetbrains.kotlin.kapt3:aptMode=$aptMode",
        s"plugin:org.jetbrains.kotlin.kapt3:sources=${javaOutput}",
        s"plugin:org.jetbrains.kotlin.kapt3:classes=${classesOutput}",
        s"plugin:org.jetbrains.kotlin.kapt3:stubs=${stubsOutput}",
        s"plugin:org.jetbrains.kotlin.kapt3:incrementalData=${incrementalDataOutput}",
        s"plugin:org.jetbrains.kotlin.kapt3:correctErrorTypes=${kaptCorrectErrorTypes()}",
        s"plugin:org.jetbrains.kotlin.kapt3:mapDiagnosticLocations=${kaptMapDiagnosticLocations()}"
      ) ++
        kaptProcessorClasspath()
          .map(_.path.toString)
          .map(path => s"plugin:org.jetbrains.kotlin.kapt3:apclasspath=$path")

      val compilerArgs = Seq(
        Seq("-d", phaseClassesOutput.toString()),
        Seq("-Xmulti-platform"),
        when(compileCp.nonEmpty)(
          "-classpath",
          compileCp.mkString(File.pathSeparator)
        ),
        when(kotlinExplicitApi())(
          "-Xexplicit-api=strict"
        ),
        allKotlincOptions(),
        apClasspathOpts.flatMap(opt => Seq("-P", opt))
      ).flatten

      Task.log.info(
        s"Running KAPT for ${sourceFiles.size} Kotlin sources to ${javaOutput.relativeTo(moduleDir)} ..."
      )

      val useBtApi = kotlincUseBtApi() && kotlinUseEmbeddableCompiler()
      if (kotlincUseBtApi() && !kotlinUseEmbeddableCompiler()) {
        Task.log.warn(
          "Kotlin Build Tools API requires kotlinUseEmbeddableCompiler=true; " +
            "falling back to CLI compiler backend for KAPT generation."
        )
      }

      KotlinWorkerManager.kotlinWorker().withValue(kotlinCompilerClasspath()) {
        _.compile(
          target = KotlinWorkerTarget.Jvm,
          useBtApi = useBtApi,
          args = compilerArgs,
          sources = sourceFiles
        )
      } match {
        case Result.Success(_) =>
        case f: Result.Failure => throw new Result.Exception(f.error, Some(f))
      }

      GeneratedKaptSources(
        java = PathRef(javaOutput),
        classes = PathRef(classesOutput),
        stubs = PathRef(stubsOutput),
        incrementalData = PathRef(incrementalDataOutput)
      )
    }
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ generatedSourcesWithKapt().sources
  }

  override def localRunClasspath: T[Seq[PathRef]] = Task {
    super.localRunClasspath() ++ Seq(generatedSourcesWithKapt().classes)
  }

  /**
   * Same as [[localRunClasspath]] but for use in BSP server.
   *
   * Keep in sync with [[localRunClasspath]]
   */
  @internal
  override private[mill] def bspLocalRunClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): Task[Seq[UnresolvedPath]] = Task.Anon {
    super.bspLocalRunClasspath(needsToMergeResourcesIntoCompileDest)() ++
      Seq(UnresolvedPath.ResolvedPath(generatedSourcesWithKapt().classes.path))
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (super.prepareOffline(all)() ++
      kotlincPluginJars() ++
      kaptProcessorClasspath()).distinct
  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KaptTests extends KaptModule with KotlinTests {
    override def kaptVersion: T[String] = outer.kaptVersion()
    override def kaptPluginMvnDeps: T[Seq[Dep]] = outer.kaptPluginMvnDeps()
    override def kaptProcessorMvnDeps: T[Seq[Dep]] = outer.kaptProcessorMvnDeps()
    override def kaptAptMode: T[String] = outer.kaptAptMode()
    override def kaptCorrectErrorTypes: T[Boolean] = outer.kaptCorrectErrorTypes()
    override def kaptMapDiagnosticLocations: T[Boolean] = outer.kaptMapDiagnosticLocations()
  }
}
