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
import mill.scalalib.api.{CompilationResult, JvmWorkerApi}
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.scalalib.{JavaModule, Lib, JvmWorkerModule}
import mill.util.Jvm
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
   * This is deprecated, as it's identical to [[kotlinVersion]]
   */
  @deprecated("Use kotlinVersion instead", "Mill 0.13.0-M1")
  def kotlinCompilerVersion: T[String] = Task { kotlinVersion() }

  /**
   * The compiler language version. Default is not set.
   */
  def kotlinLanguageVersion: T[String] = Task { "" }

  /**
   * The compiler API version. Default is not set.
   */
  def kotlinApiVersion: T[String] = Task { "" }

  /**
   * Flag to use explicit API check in the compiler. Default is `false`.
   */
  def kotlinExplicitApi: T[Boolean] = Task { false }

  type CompileProblemReporter = mill.api.CompileProblemReporter

  protected def jvmWorkerRef: ModuleRef[JvmWorkerModule] = jvmWorker

  protected def kotlinWorkerRef: ModuleRef[KotlinWorkerModule] = ModuleRef(KotlinWorkerModule)

  /**
   * The Java classpath resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerIvyDeps]].
   */
  def kotlinCompilerClasspath: T[Seq[PathRef]] = Task {
    val deps = kotlinCompilerIvyDeps() ++ Seq(
      Dep.millProjectModule("mill-kotlinlib-worker-impl")
    )
    defaultResolver().classpath(deps)
  }

  /**
   * Flag to enable the use the embeddable kotlin compiler.
   * This can be necessary to avoid classpath conflicts or ensure
   * compatibility to the used set of plugins.
   *
   * The difference between the standard compiler and the embedded compiler is,
   * that the embedded compiler comes as a dependency-free JAR.
   * All its dependencies are shaded and thus relocated to different package names.
   * This also affects the compiler API, since relocated types may surface in the API
   * but are not compatible to their non-relocated versions.
   * E.g. the plugin's dependencies need to line up with the embeddable compiler's
   * shading, otherwise a [[java.lang.AbstractMethodError]] will be thrown.
   *
   * See also https://discuss.kotlinlang.org/t/kotlin-compiler-embeddable-vs-kotlin-compiler/3196
   */
  def kotlinUseEmbeddableCompiler: Task[Boolean] = Task { false }

  /**
   * The Ivy/Coursier dependencies resembling the Kotlin compiler.
   *
   * Default is derived from [[kotlinCompilerVersion]] and [[kotlinUseEmbeddableCompiler]].
   */
  def kotlinCompilerIvyDeps: T[Seq[Dep]] = Task {
    val useEmbeddable = kotlinUseEmbeddableCompiler()
    val kv = kotlinCompilerVersion()
    val isOldKotlin = Seq("1.0.", "1.1.", "1.2.0", "1.2.1", "1.2.2", "1.2.3", "1.2.4")
      .exists(prefix => kv.startsWith(prefix))

    val compilerDep = if (useEmbeddable) {
      ivy"org.jetbrains.kotlin:kotlin-compiler-embeddable:${kv}"
    } else {
      ivy"org.jetbrains.kotlin:kotlin-compiler:${kv}"
    }

    val scriptCompilerDep = if (useEmbeddable) {
      ivy"org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${kv}"
    } else {
      ivy"org.jetbrains.kotlin:kotlin-scripting-compiler:${kv}"
    }

    Seq(compilerDep) ++ when(!isOldKotlin)(scriptCompilerDep)
  }

  /**
   * Compiler Plugin dependencies.
   */
  def kotlincPluginIvyDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * The resolved plugin jars
   */
  def kotlincPluginJars: T[Seq[PathRef]] = Task {
    val jars = defaultResolver().classpath(
      kotlincPluginIvyDeps()
        // Don't resolve transitive jars
        .map(d => d.exclude("*" -> "*"))
    )
    jars.toSeq
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
    defaultResolver().classpath(
      Seq(
        ivy"org.jetbrains.dokka:dokka-cli:${dokkaVersion()}"
      )
    )
  }

  private def dokkaPluginsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
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

  protected def when[T](cond: Boolean)(args: T*): Seq[T] = if (cond) args else Seq.empty

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
          worker = jvmWorkerRef().worker(),
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
          allKotlincOptions(),
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
  def kotlincOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Mandatory command-line options to pass to the Kotlin compiler
   * that shouldn't be removed by overriding `scalacOptions`
   */
  protected def mandatoryKotlincOptions: T[Seq[String]] = Task {
    val languageVersion = kotlinLanguageVersion()
    val kotlinkotlinApiVersion = kotlinApiVersion()
    val plugins = kotlincPluginJars().map(_.path)

    Seq("-no-stdlib") ++
      when(!languageVersion.isBlank)("-language-version", languageVersion) ++
      when(!kotlinkotlinApiVersion.isBlank)("-api-version", kotlinkotlinApiVersion) ++
      plugins.map(p => s"-Xplugin=$p")
  }

  /**
   * Aggregation of all the options passed to the Kotlin compiler.
   * In most cases, instead of overriding this Target you want to override `kotlincOptions` instead.
   */
  def allKotlincOptions: T[Seq[String]] = Task {
    mandatoryKotlincOptions() ++ kotlincOptions()
  }

  private[kotlinlib] def internalCompileJavaFiles(
      worker: JvmWorkerApi,
      upstreamCompileOutput: Seq[CompilationResult],
      javaSourceFiles: Seq[os.Path],
      compileCp: Seq[os.Path],
      javacOptions: Seq[String],
      compileProblemReporter: Option[CompileProblemReporter],
      reportOldProblems: Boolean
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
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
    override def kotlincPluginIvyDeps: T[Seq[Dep]] =
      Task { outer.kotlincPluginIvyDeps() }
      // TODO: make Xfriend-path an explicit setting
    override def kotlincOptions: T[Seq[String]] = Task {
      outer.kotlincOptions().filterNot(_.startsWith("-Xcommon-sources")) ++
        Seq(s"-Xfriend-paths=${outer.compile().classes.path.toString()}")
    }
    override def kotlinUseEmbeddableCompiler: Task[Boolean] =
      Task.Anon { outer.kotlinUseEmbeddableCompiler() }
  }

}
