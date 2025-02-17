package mill.kotlinlib.ksp

import mill.*
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
trait KspModule extends KotlinModule {

  def symbolProcessingVersion: T[String]

  /**
   * Mandatory plugins that are needed for KSP to work.
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   *
   * @return
   */
  def kspPlugins: T[Agg[Dep]] = Task {
    Agg(
      ivy"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${symbolProcessingVersion()}",
      ivy"com.google.devtools.ksp:symbol-processing:${kotlinVersion()}-${symbolProcessingVersion()}"
    )
  }

  def kspProjectBaseDir: T[PathRef] = Task {
    PathRef(moduleDir)
  }

  /** KSP output dir */
  def kspOutputDir: T[PathRef] = Task {
    PathRef(T.dest / "generated" / "ksp" / "main")
  }

  /** KSP caches dir */
  def kspCachesDir: T[PathRef] = Task {
    PathRef(T.dest / "main")
  }

  /** ksp generated sources */
  private def kspGeneratedSources: T[Seq[PathRef]] = { generateSourcesWithKSP }

  override def generatedSources: T[Seq[PathRef]] = Task {
    kspGeneratedSources() ++ super.generatedSources()
  }

  final def kspPluginsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(kspPlugins())
  }

  def kotlinCompilerPlugins: T[Agg[Dep]] = Task {
    kspPlugins()
  }

  def kotlinCompilerPluginsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(kotlinCompilerPlugins())
  }

  /**
   * The symbol processors to be used by the Kotlin compiler.
   * Default is empty.
   */
  def kotlinSymbolProcessors: T[Agg[Dep]] = Task {
    Agg.empty[Dep]
  }

  def kotlinSymbolProcessorsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      kotlinSymbolProcessors()
    )
  }

  /**
   * The symbol processing plugin id
   */
  def kotlinSymbolProcessorId: T[String] = Task {
    "com.google.devtools.ksp.symbol-processing"
  }

  override def kotlinCompilerEmbeddable: Task[Boolean] = Task { true }

  /*
   * The actual Kotlin compile task with KSP. If ksp is enabled, this runs first to
   * create the generated sources and then we run the compile task without the
   * KSP processors
   */
  private def generateSourcesWithKSP = Task {
    val sourceFiles = sources().map(_.path)

    val compileCp = compileClasspath().map(_.path).filter(os.exists)

    val pluginArgs: String = kspPluginsResolved().map(_.path)
      .mkString(",")

    val xPluginArg = s"-Xplugin=$pluginArgs"

    val pluginOpt = s"plugin:${kotlinSymbolProcessorId()}"

    val apClasspath = kotlinSymbolProcessorsResolved().map(_.path).mkString(File.pathSeparator)

    val kspOut = kspOutputDir().path

    val pluginConfigs = Seq(
      s"$pluginOpt:apclasspath=$apClasspath",
      s"$pluginOpt:projectBaseDir=${kspProjectBaseDir().path}",
      s"$pluginOpt:classOutputDir=${kspOut / "classes"}",
      s"$pluginOpt:javaOutputDir=${kspOut / "java"}",
      s"$pluginOpt:kotlinOutputDir=${kspOut / "kotlin"}",
      s"$pluginOpt:resourceOutputDir=${kspOut / "resources"}",
      s"$pluginOpt:kspOutputDir=${kspOut}",
      s"$pluginOpt:cachesDir=${kspCachesDir}",
      s"$pluginOpt:incremental=true",
      s"$pluginOpt:allWarningsAsErrors=false",
      s"$pluginOpt:returnOkOnError=true",
      s"$pluginOpt:mapAnnotationArgumentsInJava=false"
    ).mkString(",")

    val kspCompilerArgs = Seq(xPluginArg) ++ Seq("-P", pluginConfigs)

    Task.log.info(
      s"Running Kotlin Symbol Processing for ${sourceFiles.size} Kotlin sources to ${kspOut} ..."
    )

    val compilerArgs: Seq[String] = Seq(
      // destdir
      Seq("-d", kspOutputDir.toString()),
      // classpath
      when(compileCp.iterator.nonEmpty)(
        "-classpath",
        compileCp.iterator.mkString(File.pathSeparator)
      ),
      kotlincOptions(),
      kspCompilerArgs,
      // parameters
      sourceFiles.map(_.toString())
    ).flatten

    // currently if we don't delete the already generated sources
    // several layers are problematic such as the KSP giving a FileAlreadyExists
    // and test compilation complaining about duplicate classes
    // TODO maybe find a better way to do this
    os.remove.all(kspOut)

    kotlinWorkerTask().compile(KotlinWorkerTarget.Jvm, compilerArgs)

    os.walk(kspOut).filter(os.isFile).map(PathRef(_))
  }
}
