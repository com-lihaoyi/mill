/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill
package kotlinlib

import mill.api.{PathRef, Result, internal}
import mill.define.{Command, ModuleRef, Task}
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}
import mill.scalalib.api.{CompilationResult, ZincWorkerApi}
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.scalalib.{JavaModule, Lib, ZincWorkerModule}
import mill.util.Jvm
import mill.util.MillModuleUtil.millProjectModule
import mill.T

import java.io.File

trait KotlinModule extends JavaModule { outer =>

  /**
   * All individual source files fed into the compiler.
   */
  override def allSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("kt", "kts", "java")).map(PathRef(_))
  }

  /**
   * All individual Java source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  def allJavaSourceFiles: T[Seq[PathRef]] = Task {
    allSourceFiles().filter(_.path.ext.toLowerCase() == "java")
  }

  /**
   * All individual Kotlin source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  def allKotlinSourceFiles: T[Seq[PathRef]] = Task {
    allSourceFiles().filter(path => Seq("kt", "kts").contains(path.path.ext.toLowerCase()))
  }

  /**
   * The Kotlin version to be used (for API and Language level settings).
   */
  def kotlinVersion: T[String]

  /**
   * The dependencies of this module.
   * Defaults to add the kotlin-stdlib dependency matching the [[kotlinVersion]].
   */
  override def mandatoryIvyDeps: T[Seq[Dep]] = Task {
    super.mandatoryIvyDeps() ++ Seq(
      ivy"org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion()}"
    )
  }

  /**
   * The version of the Kotlin compiler to be used.
   * Default is derived from [[kotlinVersion]].
   */
  def kotlinCompilerVersion: T[String] = Task { kotlinVersion() }

  /**
   * The compiler language version. Default is not set.
   */
  def kotlinLanguageVersion: T[String] = Task { "" }

  /**
   * The compiler API version. Default is not set.
   */
  def kotlinApiVersion: T[String] = Task { "" }

  def symbolProcessingVersion: T[String] = Task { "1.0.29" }

  /**
   * Flag to use explicit API check in the compiler. Default is `false`.
   */
  def kotlinExplicitApi: T[Boolean] = Task { false }

  /** Flag to use KSP */
  def kotlinSymbolProcessing: Boolean = false

  def kspProjectBaseDir: T[PathRef] = Task { PathRef(moduleDir) }

  /** KSP output dir */
  def kspOutputDir: T[PathRef] = Task {
    PathRef(T.dest / "generated" / "ksp" / "main")
  }

  /** KSP caches dir */
  def kspCachesDir: T[PathRef] = Task {
    PathRef(T.dest / "main")
  }

  /** ksp generated sources */
  private def kspGeneratedSources: T[Seq[PathRef]] = {
    if (kotlinSymbolProcessing)
      generateSourcesWithKSP
    else {
      Task(Seq.empty[PathRef])
    }
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    kspGeneratedSources() ++ super.generatedSources()
  }

  /**
   * Mandatory plugins that are needed for KSP to work.
   * For more info go to [[https://kotlinlang.org/docs/ksp-command-line.html]]
   * @return
   */
  def kspPlugins: T[Agg[Dep]] = Task {
    if (kotlinSymbolProcessing) {
      Agg(
        ivy"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${symbolProcessingVersion()}",
        ivy"com.google.devtools.ksp:symbol-processing:${kotlinVersion()}-${symbolProcessingVersion()}"
      )
    } else Agg.empty[Dep]
  }

  final def kspPluginsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(kspPlugins())
  }

  type CompileProblemReporter = mill.api.CompileProblemReporter

  protected def zincWorkerRef: ModuleRef[ZincWorkerModule] = zincWorker

  protected def kotlinWorkerRef: ModuleRef[KotlinWorkerModule] = ModuleRef(KotlinWorkerModule)

  private[kotlinlib] def kotlinWorkerClasspath = Task {
    millProjectModule(
      "mill-kotlinlib-worker-impl",
      repositoriesTask()
    )
  }

  /**
   * The Java classpath resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerIvyDeps]].
   */
  def kotlinCompilerClasspath: T[Seq[PathRef]] = Task {
    resolveDeps(
      Task.Anon { kotlinCompilerIvyDeps().map(bindDependency()) }
    )().toSeq ++ kotlinWorkerClasspath()
  }

  def kotlinCompilerPlugins: T[Agg[Dep]] = Task { kspPlugins() }

  def kotlinCompilerPluginsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(kotlinCompilerPlugins())
  }

  /**
   * The symbol processors to be used by the Kotlin compiler.
   * Default is empty.
   */
  def kotlinSymbolProcessors: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  def kotlinSymbolProcessorsResolved: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      kotlinSymbolProcessors()
    )
  }

  /**
   * The symbol processing plugin id
   */
  def kotlinSymbolProcessorId: T[String] = Task { "com.google.devtools.ksp.symbol-processing" }

  /**
   * If ksp plugins are used, switch to embeddable to avoid
   * any classpath conflicts.
   *
   * TODO consider switching to embeddable permanently
   *
   * @return kotlin-compiler or kotlin-compiler-embeddable dependency
   */
  def kotlinCompilerDep: T[Dep] = Task {
    if (kotlinSymbolProcessing)
      ivy"org.jetbrains.kotlin:kotlin-compiler-embeddable:${kotlinCompilerVersion()}"
    else
      ivy"org.jetbrains.kotlin:kotlin-compiler:${kotlinCompilerVersion()}"
  }

  /**
   * If ksp plugins are used, switch to embeddable to avoid
   * any classpath conflicts.
   *
   * TODO consider switching to embeddable permanently
   *
   * @return kotlin-scripting-compiler or kotlin-scripting-compiler-embeddable dependency
   */
  def kotlinScriptingCompilerDep: T[Dep] = Task {
    if (kotlinSymbolProcessing)
      ivy"org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${kotlinCompilerVersion()}"
    else
      ivy"org.jetbrains.kotlin:kotlin-scripting-compiler:${kotlinCompilerVersion()}"
  }

  /**
   * The Ivy/Coursier dependencies resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerVersion]].
   */
  def kotlinCompilerIvyDeps: T[Seq[Dep]] = Task {
    Seq(kotlinCompilerDep()) ++
      (
        if (
          !Seq("1.0.", "1.1.", "1.2.0", "1.2.1", "1.2.2", "1.2.3", "1.2.4").exists(prefix =>
            kotlinVersion().startsWith(prefix)
          )
        )
          Seq(kotlinScriptingCompilerDep())
        else Seq()
      )
  }

  def kotlinWorkerTask: Task[KotlinWorker] = Task.Anon {
    kotlinWorkerRef().kotlinWorkerManager().get(kotlinCompilerClasspath())
  }

  /**
   * Compiles all the sources to JVM class files.
   */
  override def compile: T[CompilationResult] = Task {
    kotlinCompileTask()()
  }

  /**
   * Runs the Kotlin compiler with the `-help` argument to show you the built-in cmdline help.
   * You might want to add additional arguments like `-X` to see extra help.
   */
  def kotlincHelp(args: String*): Command[Unit] = Task.Command {
    kotlinCompileTask(Seq("-help") ++ args)()
    ()
  }

  /**
   * The documentation jar, containing all the Dokka HTML files, for
   * publishing to Maven Central. You can control Dokka version by using [[dokkaVersion]]
   * and option by using [[dokkaOptions]].
   */
  override def docJar: T[PathRef] = T[PathRef] {
    val outDir = Task.dest

    val dokkaDir = outDir / "dokka"
    os.makeDir.all(dokkaDir)

    val files = Lib.findSourceFiles(docSources(), Seq("java", "kt"))

    if (files.nonEmpty) {
      val pluginClasspathOption = Seq(
        "-pluginsClasspath",
        // `;` separator is used on all platforms!
        dokkaPluginsClasspath().map(_.path).mkString(";")
      )
      val depClasspath = (compileClasspath() ++ runClasspath())
        .filter(p => os.exists(p.path))
        .map(_.path.toString()).mkString(";")

      // TODO need to provide a dedicated source set for common sources in case of Multiplatform
      // platforms supported: jvm, js, wasm, native, common
      val options = dokkaOptions() ++
        Seq("-outputDir", dokkaDir.toString()) ++
        pluginClasspathOption ++
        Seq(
          s"-sourceSet",
          Seq(
            s"-src ${docSources().map(_.path).filter(os.exists).mkString(";")}",
            s"-displayName $dokkaSourceSetDisplayName",
            s"-classpath $depClasspath",
            s"-analysisPlatform $dokkaAnalysisPlatform"
          ).mkString(" ")
        )

      Task.log.info("dokka options: " + options)

      Jvm.callProcess(
        mainClass = "",
        classPath = Seq.empty,
        jvmArgs = Seq("-jar", dokkaCliClasspath().head.path.toString()),
        mainArgs = options,
        stdin = os.Inherit,
        stdout = os.Inherit
      )
    }

    PathRef(Jvm.createJar(outDir / "out.jar", Seq(dokkaDir)))
  }

  /**
   * Additional options to be used by the Dokka tool.
   * You should not set the `-outputDir` setting for specifying the target directory,
   * as that is done in the [[docJar]] target.
   */
  def dokkaOptions: T[Seq[String]] = Task { Seq[String]() }

  /**
   * Dokka version.
   */
  def dokkaVersion: T[String] = Task {
    Versions.dokkaVersion
  }

  /**
   * Classpath for running Dokka.
   */
  private def dokkaCliClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Seq(
        ivy"org.jetbrains.dokka:dokka-cli:${dokkaVersion()}"
      )
    )
  }

  private def dokkaPluginsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Seq(
        ivy"org.jetbrains.dokka:dokka-base:${dokkaVersion()}",
        ivy"org.jetbrains.dokka:analysis-kotlin-descriptors:${dokkaVersion()}",
        Dep.parse(Versions.kotlinxHtmlJvmDep),
        Dep.parse(Versions.freemarkerDep)
      )
    )
  }

  protected def dokkaAnalysisPlatform: String = "jvm"
  protected def dokkaSourceSetDisplayName: String = "jvm"

  protected def when(cond: Boolean)(args: String*): Seq[String] = if (cond) args else Seq()

  /**
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

  /**
   * The actual Kotlin compile task (used by [[compile]] and [[kotlincHelp]]).
   */
  protected def kotlinCompileTask(extraKotlinArgs: Seq[String] = Seq()): Task[CompilationResult] =
    Task.Anon {
      val ctx = Task.ctx()
      val dest = ctx.dest
      val classes = dest / "classes"
      os.makeDir.all(classes)

      val javaSourceFiles = allJavaSourceFiles().map(_.path)
      val kotlinSourceFiles = allKotlinSourceFiles().map(_.path)

      val isKotlin = kotlinSourceFiles.nonEmpty
      val isJava = javaSourceFiles.nonEmpty
      val isMixed = isKotlin && isJava

      val compileCp = compileClasspath().map(_.path).filter(os.exists)
      val updateCompileOutput = upstreamCompileOutput()

      def compileJava: Result[CompilationResult] = {
        ctx.log.info(
          s"Compiling ${javaSourceFiles.size} Java sources to ${classes} ..."
        )
        // The compile step is lazy, but its dependencies are not!
        internalCompileJavaFiles(
          worker = zincWorkerRef().worker(),
          upstreamCompileOutput = updateCompileOutput,
          javaSourceFiles = javaSourceFiles,
          compileCp = compileCp,
          javacOptions = javacOptions(),
          compileProblemReporter = ctx.reporter(hashCode),
          reportOldProblems = internalReportOldProblems()
        )
      }

      if (isMixed || isKotlin) {
        ctx.log.info(
          s"Compiling ${kotlinSourceFiles.size} Kotlin sources to ${classes} ..."
        )
        val compilerArgs: Seq[String] = Seq(
          // destdir
          Seq("-d", classes.toString()),
          // apply multi-platform support (expect/actual)
          // TODO if there is penalty for activating it in the compiler, put it behind configuration flag
          Seq("-Xmulti-platform"),
          // classpath
          when(compileCp.iterator.nonEmpty)(
            "-classpath",
            compileCp.iterator.mkString(File.pathSeparator)
          ),
          when(kotlinExplicitApi())(
            "-Xexplicit-api=strict"
          ),
          kotlincOptions(),
          extraKotlinArgs,
          // parameters
          (kotlinSourceFiles ++ javaSourceFiles).map(_.toString())
        ).flatten

        val workerResult = kotlinWorkerTask().compile(KotlinWorkerTarget.Jvm, compilerArgs)

        val analysisFile = dest / "kotlin.analysis.dummy"
        os.write(target = analysisFile, data = "", createFolders = true)

        workerResult match {
          case Result.Success(_) =>
            val cr = CompilationResult(analysisFile, PathRef(classes))
            if (!isJava) {
              // pure Kotlin project
              cr
            } else {
              // also run Java compiler and use it's returned result
              compileJava
            }
          case Result.Failure(reason) => Result.Failure(reason)
        }
      } else {
        // it's Java only
        compileJava
      }
    }

  /**
   * Additional Kotlin compiler options to be used by [[compile]].
   */
  def kotlincOptions: T[Seq[String]] = Task {
    val options = Seq.newBuilder[String]
    options += "-no-stdlib"
    val languageVersion = kotlinLanguageVersion()
    if (!languageVersion.isBlank) {
      options += "-language-version"
      options += languageVersion
    }
    val kotlinkotlinApiVersion = kotlinApiVersion()
    if (!kotlinkotlinApiVersion.isBlank) {
      options += "-api-version"
      options += kotlinkotlinApiVersion
    }
    options.result()
  }

  private[kotlinlib] def internalCompileJavaFiles(
      worker: ZincWorkerApi,
      upstreamCompileOutput: Seq[CompilationResult],
      javaSourceFiles: Seq[os.Path],
      compileCp: Seq[os.Path],
      javacOptions: Seq[String],
      compileProblemReporter: Option[CompileProblemReporter],
      reportOldProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): Result[CompilationResult] = {
    worker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = javaSourceFiles,
      compileClasspath = compileCp,
      javacOptions = javacOptions,
      reporter = compileProblemReporter,
      reportCachedProblems = reportOldProblems,
      incrementalCompilation = true
    )
  }

  private[kotlinlib] def internalReportOldProblems: Task[Boolean] = zincReportCachedProblems

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(BspModule.LanguageId.Java, BspModule.LanguageId.Kotlin),
    canCompile = true,
    canRun = true
  )

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KotlinTests extends JavaTests with KotlinModule {

    override def kotlinLanguageVersion: T[String] = outer.kotlinLanguageVersion()
    override def kotlinApiVersion: T[String] = outer.kotlinApiVersion()
    override def kotlinExplicitApi: T[Boolean] = false
    override def kotlinVersion: T[String] = Task { outer.kotlinVersion() }
    override def kotlinCompilerVersion: T[String] = Task { outer.kotlinCompilerVersion() }
    override def kotlincOptions: T[Seq[String]] = Task {
      outer.kotlincOptions().filterNot(_.startsWith("-Xcommon-sources")) ++
        Seq(s"-Xfriend-paths=${outer.compile().classes.path.toString()}")
    }
  }

}
