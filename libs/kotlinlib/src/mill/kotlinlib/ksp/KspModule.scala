package mill.kotlinlib.ksp

import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import mill.*
import mill.api.{ModuleRef, PathRef, Task}
import mill.kotlinlib.ksp2.{KspWorker, KspWorkerArgs}
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule, KotlinWorkerManager}
import mill.util.{Jvm, Version}

import java.io.File

/**
 * Trait for KSP (Kotlin Symbol Processing) modules.
 *
 * To use KSP 2, which supports Kotlin 2.0 and later, use `def ksmModuleMode = Ksp2Cli`.
 * KSP 2 Documentation: https://github.com/google/ksp/blob/main/docs/ksp2cmdline.md
 *
 * For the older KSP 1 which supports Kotlin language versions up to 1.9, use `def kspModuleMode = Ksp1`.
 * For KSP 1, the use of kotlin-compiler-embedded is also recommended (and thus enabled by default)
 * to avoid any classpath conflicts between the compiler and user defined plugins!
 * You can also use KspModuleMode.Ksp1 with Kotlin 2.x,
 * but you will need to set the kspLanguageVersion to 1.9 or earlier.
 */
@mill.api.experimental
trait KspModule extends KotlinModule { outer =>

  /**
   * Controls the mechanism in which the Kotlin Symbol Processing is run.
   * [[KspModuleMode.Ksp1]] works with the embeddable kotlin compiler and via the KSP compiler plugin.
   * In case of Ksp1 choice, you need to set [[kspLanguageVersion]] to 1.9 or earlier, the compiler flags
   * via [[ksp1KotlincOptions]]. The mandatory KSP plugins are automatically added via [[ksp1Plugins]].
   *
   * [[KspModuleMode.Ksp2Cli]] works with the KSP 2 command line tool `com.google.devtools.ksp.cmdline.KSPJvmMain`
   * which is run in a separate JVM process.
   * [[KspModuleMode.Ksp2]] works with an internal worker that runs KSP in the same JVM as Mill. This is the recommended
   * way to run KSP 2, as it is faster than the CLI mode and doesn't have the cli limitations of Ksp2Cli (e.g. exceeding
   * character limit on Windows).
   */
  def kspModuleMode: KspModuleMode = KspModuleMode.Ksp2

  /**
   * The version of the symbol processing library to use, which needs to be compatible with
   * the Kotlin version used.
   *
   * For finding the right versions, also see [[https://github.com/google/ksp/releases]]
   *
   * @return
   */
  def kspVersion: T[String] = kotlinVersion()

  /**
   * The version of the Kotlin language to use for the KSP stage.
   * For KSP 1.x, this should be 1.9 or earlier.
   * For KSP 2.x, this should be 2.0 or later.
   *
   * The KSP language version used for the KSP compilation stage.
   * * [[kspApiVersion]] must be less than or equal to this version.
   */
  def kspLanguageVersion: T[String] = Task { kspVersion().split("[.]").take(2).mkString(".") }

  /**
   * The KSP api version used for the KSP compilation stage.
   */
  def kspApiVersion: T[String] = kspLanguageVersion()

  /**
   * The JVM target version for the KSP compilation step.
   */
  def kspJvmTarget: T[String]

  /**
   * The symbol processors to be used by the Kotlin compiler.
   * Default is empty.
   */
  def kotlinSymbolProcessors: T[Seq[Dep]] = Task {
    Seq.empty[Dep]
  }

  /**
   * Resolved classpath for the symbol processors.
   */
  def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors(),
      boms = allBomDeps()
    )
  }

  private[mill] def addJvmVariantAttributes: ResolutionParams => ResolutionParams = { params =>
    params.addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" -> VariantMatcher.Equals("jvm"),
      "org.gradle.jvm.environment" -> VariantMatcher.Equals("standard-jvm")
    )
  }

  /**
   * The classpath when running Kotlin Symbol processing. Default is this module's compile classpath.
   */
  def kspClasspath: T[Seq[PathRef]] = compileClasspath()

  /**
   * Processor options to be passed to KSP.
   */
  def kspProcessorOptions: T[Map[String, String]] = Task {
    Map.empty[String, String]
  }

  /**
   * The module name to be used by KSP.
   * Default is the same as the module name of this module.
   */
  def kspModuleName = moduleSegments.render

  /**
   * The sources for being used in KSP, in case
   * the user wants to separate KSP specific sources
   * from others. Defaults to [[sources]] (i.e. no splitting)
   */
  def kspSources: T[Seq[PathRef]] = Task {
    sources()
  }

  override def kotlinUseEmbeddableCompiler: Task[Boolean] = kspModuleMode match {
    case KspModuleMode.Ksp1 => Task { true }
    case KspModuleMode.Ksp2Cli | KspModuleMode.Ksp2 => Task { super.kotlinUseEmbeddableCompiler() }
  }

  /**
   * Generated sources from KSP processing.
   */
  def generatedSourcesWithKsp: T[GeneratedKspSources] = kspModuleMode match {
    case KspModuleMode.Ksp1 => generatedSourcesWithKsp1()
    case KspModuleMode.Ksp2 => generatedSourcesWithKsp2()
    case KspModuleMode.Ksp2Cli => generatedSourcesWithKsp2Cli()
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ generatedSourcesWithKsp().sources
  }

  ///////////////////////////////////////////////
  // KSP 1 tasks

  /**
   * Kotlinc arguments used with KSP. Sets the language version for the
   * ksp processing stage using [[kspLanguageVersion]]. [[kspLanguageVersion]] needs
   * to be 1.9 or earlier for KSP 1.x.
   *
   * @return
   */
  def ksp1KotlincOptions: T[Seq[String]] = Task {
    if (!kspLanguageVersion().startsWith("1.")) {
      throw new RuntimeException("KSP needs a compatible language version <= 1.9 to be set!")
    }
    kotlincOptions() ++ Seq(
      "-Xallow-unstable-dependencies",
      "-no-reflect",
      "-no-stdlib",
      "-language-version",
      kspLanguageVersion()
    )
  }

  /**
   * Mandatory plugins that are needed for KSP to work.
   * These are:
   * - com.google.devtools.ksp:symbol-processing-api
   * - com.google.devtools.ksp:symbol-processing
   *
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   */
  def ksp1Plugins: T[Seq[Dep]] = Task {
    Seq(
      mvn"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${kspVersion()}",
      mvn"com.google.devtools.ksp:symbol-processing:${kotlinVersion()}-${kspVersion()}"
    )
  }

  /** The symbol processing plugin id */
  private val ksp1PluginId: String = "com.google.devtools.ksp.symbol-processing"

  def ksp1PluginsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      ksp1Plugins(),
      resolutionParamsMapOpt = Some(addJvmVariantAttributes)
    )
  }

  /**
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task.
   */
  def generatedSourcesWithKsp1: T[GeneratedKspSources] = Task {
    val sourceFiles = kspSources().map(_.path).filter(os.exists)

    val compileCp = kspClasspath().map(_.path).filter(os.exists)

    val pluginArgs: String = ksp1PluginsResolved().map(_.path)
      .mkString(",")

    val xPluginArg = s"-Xplugin=$pluginArgs"

    val pluginOpt = s"plugin:${ksp1PluginId}"

    val apClasspath = kotlinSymbolProcessorsResolved().map(_.path).mkString(File.pathSeparator)

    val kspPluginParameters = kspProcessorOptions().map {
      case (key, value) => s"apoption=$key=$value"
    }.toSeq

    val kspOutputDir = Task.dest / "generated"

    val kspCachesDir = Task.dest / "caches"
    val java = kspOutputDir / "java"
    val kotlin = kspOutputDir / "kotlin"
    val resources = kspOutputDir / "resources"
    val classes = kspOutputDir / "classes"
    val pluginConfigs = Seq(
      s"$pluginOpt:apclasspath=$apClasspath",
      s"$pluginOpt:projectBaseDir=${moduleDir.toString}",
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
      ksp1KotlincOptions() ++ Seq(xPluginArg) ++ Seq("-P", pluginConfigs.mkString(","))

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

    val compilerArgs: Seq[String] = classpath ++ kspCompilerArgs

    Task.log.info(s"KSP arguments: ${compilerArgs.mkString(" ")}")

    val useBtApi = kotlincUseBtApi() && kotlinUseEmbeddableCompiler()
    if (kotlincUseBtApi() && !kotlinUseEmbeddableCompiler()) {
      Task.log.warn(
        "Kotlin Build Tools API requires kotlinUseEmbeddableCompiler=true; " +
          "falling back to CLI compiler backend for KSP generation."
      )
    }

    KotlinWorkerManager.kotlinWorker().withValue(kotlinCompilerClasspath()) {
      _.compile(
        target = KotlinWorkerTarget.Jvm,
        useBtApi = useBtApi,
        args = compilerArgs,
        sources = sourceFiles
      )
    }

    GeneratedKspSources(PathRef(java), PathRef(kotlin), PathRef(resources), PathRef(classes))
  }

  ///////////////////////////////////////////////
  // KSP 2 tasks

  /**
   * Any extra args passed to the KSP (when run in forked mode)
   * com.google.devtools.ksp.cmdline.KSPJvmMain
   *
   * For more info go to [[https://github.com/google/ksp/blob/main/docs/ksp2cmdline.md]]
   */
  def ksp2Args: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /**
   * The jars needed to run KSP 2 via `com.google.devtools.ksp.cmdline.KSPJvmMain` or
   * the in-process worker via [[KspWorkerModule]].
   *
   * The versions are computed from [[kotlinVersion]]-[[kspVersion]]
   *
   * For finding the right versions, also see [[https://github.com/google/ksp/releases]]
   * For more info go to [[https://github.com/google/ksp/blob/main/docs/ksp2cmdline.md]]
   */
  def ksp2ToolsDeps: T[Seq[Dep]] = Task {
    val kspVer = kspVersion()
    val kspDepVer =
      if (Version.isAtLeast(kspVer, "2.3")(using Version.MavenOrdering)) {
        kspVer
      } else {
        s"${kotlinVersion()}-$kspVer"
      }
    Seq(
      mvn"com.google.devtools.ksp:symbol-processing-aa-embeddable:${kspDepVer}",
      mvn"com.google.devtools.ksp:symbol-processing-api:${kspDepVer}",
      mvn"com.google.devtools.ksp:symbol-processing-common-deps:${kspDepVer}",
      mvn"org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    )
  }

  def ksp2ToolsDepsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      ksp2ToolsDeps(),
      resolutionParamsMapOpt = Some(addJvmVariantAttributes)
    )
  }

  /**
   * The classpath used to run KSP 2 in-process worker mode, which is provided via
   * [[KspWorkerModule]]. It includes the KSP 2 API provided from [[ksp2ToolsDeps]]
   * and mill-libs-kotlinlib-ksp2 module with the worker that executes the SymbolProcessingProviders.
   */
  def ksp2InProgramToolsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-libs-kotlinlib-ksp2")
      ) ++ ksp2ToolsDeps(),
      resolutionParamsMapOpt = Some(addJvmVariantAttributes)
    )
  }

  /**
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task.
   */
  private def generatedSourcesWithKsp2Cli: T[GeneratedKspSources] = Task {

    val processorResolvedClasspath = kotlinSymbolProcessorsResolved().map(_.path)
    val processorClasspath = processorResolvedClasspath.mkString(File.pathSeparator)

    val kspOutputDir = Task.dest / "generated"
    val java = kspOutputDir / "java"
    val kotlin = kspOutputDir / "kotlin"
    val resources = kspOutputDir / "resources"
    val classes = kspOutputDir / "classes"
    val kspCachesDir = Task.dest / "caches"

    val processorOptionsValue =
      kspProcessorOptions().map((key, value) => s"$key=$value").toSeq.mkString(File.pathSeparator)

    val processorOptions = if (processorOptionsValue.isEmpty)
      ""
    else
      s"-processor-options=${processorOptionsValue}"
    val args = Seq(
      s"-module-name=${kspModuleName}",
      "-jvm-target",
      kspJvmTarget(),
      s"-jdk-home=${System.getProperty("java.home")}",
      s"-source-roots=${kspSources().map(_.path).mkString(File.pathSeparator)}",
      s"-project-base-dir=${moduleDir.toString}",
      s"-output-base-dir=${kspOutputDir}",
      s"-caches-dir=${kspCachesDir}",
      s"-libraries=${kspClasspath().map(_.path).mkString(File.pathSeparator)}",
      s"-class-output-dir=${classes}",
      s"-kotlin-output-dir=${kotlin}",
      s"-java-output-dir=${java}",
      s"-resource-output-dir=${resources}",
      s"-language-version=${kspLanguageVersion()}",
      s"-incremental=true",
      s"-incremental-log=true",
      s"-api-version=${kspApiVersion()}",
      processorOptions,
      s"-map-annotation-arguments-in-java=false"
    ) ++ ksp2Args() :+ processorClasspath

    val kspJvmMainClasspath = ksp2ToolsDepsClasspath().map(_.path)
    val mainClass = "com.google.devtools.ksp.cmdline.KSPJvmMain"
    Task.log.debug(
      s"Running Kotlin Symbol Processing with java -cp ${kspJvmMainClasspath.mkString(File.pathSeparator)} ${mainClass} ${args.mkString(" ")}"
    )

    val jvmCall = Jvm.callProcess(
      mainClass = mainClass,
      classPath = kspJvmMainClasspath,
      mainArgs = args
    )

    Task.log.info(
      s"KSP finished with exit code: ${jvmCall.exitCode}"
    )

    Task.log.info(
      s"KSP output: ${jvmCall.out.text()}"
    )

    GeneratedKspSources(PathRef(java), PathRef(kotlin), PathRef(resources), PathRef(classes))

  }

  /**
   * The classloader with [[ksp2InProgramToolsClasspath]], which includes the KSP 2 Worker
   * with the KSP deps from [[ksp2InProgramToolsClasspath]]. This classloader is a
   * parent of [[kotlinSymbolProcessorClassloader]] as classes are shared between the [[ksp2ToolsDeps]]
   * and [[kotlinSymbolProcessors]] (symbol processors depend on the KSP API).
   */
  def ksp2WorkerClassloader: Worker[ClassLoader & AutoCloseable] = Task.Worker {
    Jvm.createClassLoader(
      classPath = ksp2InProgramToolsClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  /**
   * The in-process worker instance of the KSP 2 processor with the [[ksp2WorkerClassloader]].
   */
  def ksp2Worker: Worker[KspWorker] = Task.Worker {
    ksp2WorkerClassloader()
      .loadClass("mill.kotlinlib.ksp2.worker.KspWorkerImpl").getConstructor().newInstance()
      .asInstanceOf[KspWorker]
  }

  /**
   * The classloader with [[kotlinSymbolProcessorsResolved]] classpath. This is a child of
   * [[ksp2Worker]] classloader, as classes are shared between the [[ksp2ToolsDeps]]  and
   * [[kotlinSymbolProcessors]].
   *
   * For more info see reference implementation: [[https://github.com/google/ksp/blob/main/docs/ksp2entrypoints.md]]
   */
  def kotlinSymbolProcessorClassloader: Worker[ClassLoader & AutoCloseable] = Task.Worker {
    Jvm.createClassLoader(
      kotlinSymbolProcessorsResolved().map(_.path),
      parent = ksp2WorkerClassloader()
    )
  }

  def kspWorkerModule: ModuleRef[KspWorkerModule] = ModuleRef(KspWorkerModule)

  /**
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task. It uses an in-process worker to run KSP
   * provided from [[kspWorkerModule]]
   */
  private def generatedSourcesWithKsp2: T[GeneratedKspSources] = Task {

    val kspOutputDir = Task.dest / "generated"
    val java = kspOutputDir / "java"
    val kotlin = kspOutputDir / "kotlin"
    val resources = kspOutputDir / "resources"
    val classes = kspOutputDir / "classes"
    val kspCachesDir = Task.dest / "caches"

    val processorOptionsValue =
      kspProcessorOptions().map((key, value) => s"$key=$value").toSeq.mkString(File.pathSeparator)

    val processorOptions = if (processorOptionsValue.isEmpty)
      ""
    else
      s"-processor-options=${processorOptionsValue}"
    val args = Seq(
      s"-module-name=${kspModuleName}",
      "-jvm-target",
      kspJvmTarget(),
      s"-jdk-home=${System.getProperty("java.home")}",
      s"-source-roots=${kspSources().map(_.path).mkString(File.pathSeparator)}",
      s"-project-base-dir=${moduleDir.toString}",
      s"-output-base-dir=${kspOutputDir}",
      s"-caches-dir=${kspCachesDir}",
      s"-libraries=${kspClasspath().map(_.path).mkString(File.pathSeparator)}",
      s"-class-output-dir=${classes}",
      s"-kotlin-output-dir=${kotlin}",
      s"-java-output-dir=${java}",
      s"-resource-output-dir=${resources}",
      s"-language-version=${kspLanguageVersion()}",
      s"-incremental=true",
      s"-incremental-log=true",
      s"-api-version=${kspApiVersion()}",
      processorOptions,
      s"-map-annotation-arguments-in-java=false"
    ) ++ ksp2Args()

    val kspLogLevel = if (Task.log.debugEnabled)
      mill.kotlinlib.ksp2.LogLevel.Debug
    else
      mill.kotlinlib.ksp2.LogLevel.Warn

    kspWorkerModule().runKsp(
      KspWorkerArgs(kspLogLevel),
      ksp2Worker(),
      kotlinSymbolProcessorClassloader(),
      args
    )

    GeneratedKspSources(PathRef(java), PathRef(kotlin), PathRef(resources), PathRef(classes))

  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KspTests extends KspModule with KotlinTests {
    override def kspModuleMode: KspModuleMode = outer.kspModuleMode
    override def kspVersion: T[String] = outer.kspVersion()
    override def kspLanguageVersion: T[String] = outer.kspLanguageVersion()
    override def kspApiVersion: T[String] = outer.kspApiVersion()
    override def kspJvmTarget: T[String] = outer.kspJvmTarget()
  }
}
