package mill.kotlinlib.ksp

import mill.*
import mill.api.Result
import mill.api.{PathRef, Task}
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.kotlinlib.{Dep, DepSyntax, KotlinWorkerManager}

import java.io.File

/**
 * Sets up the kotlin compiler for using KSP (Kotlin Symbol Processing)
 * by plugging in the symbol-processing and symbol-processing-api dependencies.
 *
 * Use of kotlin-compiler-embedded is also recommended (and thus enabled by default)
 * to avoid any classpath conflicts between the compiler and user defined plugins!
 *
 * This module is based on KSP 1.x which relies on language version 1.9 or earlier.
 * For KSP 2.x, use [[Ksp2Module]] instead.
 */
@mill.api.experimental
trait KspModule extends KspBaseModule { outer =>

  /**
   * The version of the Kotlin language to use.
   * This is used to determine the KSP version to use.
   * Defaults to `1.9` which is compatible with KSP 1.x.
   */
  def kspLanguageVersion: String = "1.9"

  /**
   * The version of the symbol processing library to use.
   *
   * This is combined with the version of the kotlin compiler to pull the symbol processing
   * plugins for the compiler. These dependencies are
   *
   * com.google.devtools.ksp:symbol-processing-api
   * and
   * com.google.devtools.ksp:symbol-processing
   *
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   * @return
   */
  def kspVersion: T[String]

  /**
   * Mandatory plugins that are needed for KSP to work.
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   *
   * @return
   */
  def kspPlugins: T[Seq[Dep]] = Task {
    Seq(
      mvn"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${kspVersion()}",
      mvn"com.google.devtools.ksp:symbol-processing:${kotlinVersion()}-${kspVersion()}"
    )
  }

  def kspPluginsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(kspPlugins())
  }

  def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors()
    )
  }

  override def kotlinUseEmbeddableCompiler: Task[Boolean] = Task { true }

  /*
   * The symbol processing plugin id
   */
  private val kspPluginId: String =
    "com.google.devtools.ksp.symbol-processing"

  /**
   * The KSP ap classpath
   *
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   */
  def kspApClasspath: T[Seq[PathRef]] = Task {
    kotlinSymbolProcessorsResolved()
  }

  /**
   * The sources for being used in KSP, in case
   * the user wants to separate KSP specific sources
   * from others. Defaults to [[sources]] (i.e. no splitting)
   */
  def kspSources: T[Seq[PathRef]] = Task {
    sources()
  }

  /**
   * The classpath when running Kotlin Symbol processing
   *
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   */
  def kspClasspath: T[Seq[PathRef]] = Task {
    super.compileClasspath()
  }

  /**
   * Kotlinc arguments used with KSP
   * @return
   */
  def kspKotlincOptions: T[Seq[String]] = Task {
    if (kspLanguageVersion.isBlank) {
      throw new RuntimeException("KSP needs a compatible language version to be set!")
    }
    kotlincOptions() ++ Seq(
      "-Xallow-unstable-dependencies",
      "-no-reflect",
      "-no-stdlib",
      "-language-version",
      kspLanguageVersion
    )
  }

  @deprecated("Use `kspProcessorOptions`")
  def kspPluginParameters: T[Seq[String]] = Task {
    Seq.empty
  }

  /**
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task.
   */
  def generatedSourcesWithKSP: T[GeneratedKSPSources] = Task {
    val sourceFiles = kspSources().map(_.path).filter(os.exists)

    val compileCp = kspClasspath().map(_.path).filter(os.exists)

    val pluginArgs: String = kspPluginsResolved().map(_.path)
      .mkString(",")

    val xPluginArg = s"-Xplugin=$pluginArgs"

    val pluginOpt = s"plugin:${kspPluginId}"

    val apClasspath = kotlinSymbolProcessorsResolved().map(_.path).mkString(File.pathSeparator)

    val kspPluginParameters = kspProcessorOptions().map {
      case (key, value) => s"apoption=$key=$value"
    }.toSeq

    val kspProjectBasedDir = moduleDir
    val kspOutputDir = Task.dest / "generated/ksp/main"

    val kspCachesDir = Task.dest / "caches/main"
    val java = kspOutputDir / "java"
    val kotlin = kspOutputDir / "kotlin"
    val resources = kspOutputDir / "resources"
    val classes = kspOutputDir / "classes"
    val pluginConfigs = Seq(
      s"$pluginOpt:apclasspath=$apClasspath",
      s"$pluginOpt:projectBaseDir=${kspProjectBasedDir}",
      s"$pluginOpt:classOutputDir=${classes}",
      s"$pluginOpt:javaOutputDir=${java}",
      s"$pluginOpt:kotlinOutputDir=${kotlin}",
      s"$pluginOpt:resourceOutputDir=${resources}",
      s"$pluginOpt:kspOutputDir=${kspOutputDir}",
      s"$pluginOpt:cachesDir=${kspCachesDir}",
      s"$pluginOpt:incremental=true",
      s"${pluginOpt}:incrementalLog=false",
      s"$pluginOpt:allWarningsAsErrors=false",
      s"$pluginOpt:returnOkOnError=true",
      s"$pluginOpt:mapAnnotationArgumentsInJava=false"
    ) ++ kspPluginParameters.map(p => s"$pluginOpt:$p")

    val kspCompilerArgs =
      kspKotlincOptions() ++ Seq(xPluginArg) ++ Seq("-P", pluginConfigs.mkString(","))

    Task.log.info(
      s"Running Kotlin Symbol Processing for ${sourceFiles.size} Kotlin sources to ${kspOutputDir} ..."
    )

    val compiledSources = Task.dest / "compiled"
    os.makeDir.all(compiledSources)

    val classpath = Seq(
      // destdir
      "-d",
      compiledSources.toString,
      // classpath
      "-classpath",
      compileCp.iterator.mkString(File.pathSeparator)
    )

    val compilerArgs: Seq[String] = classpath ++ kspCompilerArgs ++ sourceFiles.map(_.toString)

    Task.log.info(s"KSP arguments: ${compilerArgs.mkString(" ")}")

    KotlinWorkerManager.kotlinWorker().withValue(kotlinCompilerClasspath()) {
      _.compile(KotlinWorkerTarget.Jvm, compilerArgs)
    }

    GeneratedKSPSources(PathRef(java), PathRef(kotlin), PathRef(resources), PathRef(classes))
  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KspTests extends KspModule with KotlinTests {
    override def kspVersion: T[String] = outer.kspVersion
  }
}

case class GeneratedKSPSources(
    java: PathRef,
    kotlin: PathRef,
    resources: PathRef,
    classes: PathRef
) {
  def sources: Seq[PathRef] = Seq(java, kotlin)
}

object GeneratedKSPSources {
  implicit def resultRW: upickle.default.ReadWriter[GeneratedKSPSources] = upickle.default.macroRW
}
