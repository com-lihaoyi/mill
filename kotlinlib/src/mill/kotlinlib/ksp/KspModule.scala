package mill.kotlinlib.ksp

import mill._
import mill.api.{PathRef, Result}
import mill.define.Task
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}

import java.io.File

/**
 * Sets up the kotlin compiler for using KSP (Kotlin Symbol Processing)
 * by plugging in the symbol-processing and symbol-processing-api dependencies.
 *
 * Use of kotlin-compiler-embedded is also recommended (and thus enabled by default)
 * to avoid any classpath conflicts between the compiler and user defined plugins!
 */
@mill.api.experimental
trait KspModule extends KotlinModule { outer =>

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
  def kspPlugins: T[Agg[Dep]] = Task {
    Agg(
      ivy"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${kspVersion()}",
      ivy"com.google.devtools.ksp:symbol-processing:${kotlinVersion()}-${kspVersion()}"
    )
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    generateSourcesWithKSP() ++ super.generatedSources()
  }

  def kspPluginsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().classpath(kspPlugins())
  }

  /**
   * The symbol processors to be used by the Kotlin compiler.
   * Default is empty.
   */
  def kotlinSymbolProcessors: T[Agg[Dep]] = Task {
    Agg.empty[Dep]
  }

  def kotlinSymbolProcessorsResolved: T[Agg[PathRef]] = Task {
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
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task.
   */
  def generateSourcesWithKSP: Target[Seq[PathRef]] = Task {
    val sourceFiles = sources().map(_.path)

    val compileCp = compileClasspath().map(_.path).filter(os.exists)

    val pluginArgs: String = kspPluginsResolved().map(_.path)
      .mkString(",")

    val xPluginArg = s"-Xplugin=$pluginArgs"

    val pluginOpt = s"plugin:${kspPluginId}"

    val apClasspath = kotlinSymbolProcessorsResolved().map(_.path).mkString(File.pathSeparator)

    val kspProjectBasedDir = moduleDir
    val kspOutputDir = T.dest / "generated/ksp/main"

    val kspCachesDir = T.dest / "caches/main"

    val pluginConfigs = Seq(
      s"$pluginOpt:apclasspath=$apClasspath",
      s"$pluginOpt:projectBaseDir=${kspProjectBasedDir}",
      s"$pluginOpt:classOutputDir=${kspOutputDir / "classes"}",
      s"$pluginOpt:javaOutputDir=${kspOutputDir / "java"}",
      s"$pluginOpt:kotlinOutputDir=${kspOutputDir / "kotlin"}",
      s"$pluginOpt:resourceOutputDir=${kspOutputDir / "resources"}",
      s"$pluginOpt:kspOutputDir=${kspOutputDir}",
      s"$pluginOpt:cachesDir=${kspCachesDir}",
      s"$pluginOpt:incremental=true",
      s"$pluginOpt:allWarningsAsErrors=false",
      s"$pluginOpt:returnOkOnError=true",
      s"$pluginOpt:mapAnnotationArgumentsInJava=false"
    ).mkString(",")

    val kspCompilerArgs = Seq(xPluginArg) ++ Seq("-P", pluginConfigs)

    Task.log.info(
      s"Running Kotlin Symbol Processing for ${sourceFiles.size} Kotlin sources to ${kspOutputDir} ..."
    )

    val compilerArgs: Seq[String] = Seq(
      // destdir
      Seq("-d", kspOutputDir.toString()),
      // classpath
      when(compileCp.iterator.nonEmpty)(
        "-classpath",
        compileCp.iterator.mkString(File.pathSeparator)
      ),
      allKotlincOptions(),
      kspCompilerArgs,
      // parameters
      sourceFiles.map(_.toString())
    ).flatten

    kotlinWorkerTask().compile(KotlinWorkerTarget.Jvm, compilerArgs)

    Seq(PathRef(kspOutputDir))
  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KspTests extends KspModule with KotlinTests {
    override def kspVersion: T[String] = outer.kspVersion
  }
}
