/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill
package kotlinlib

import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import mill.api.Result
import mill.api.ModuleRef
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.javalib.api.CompilationResult
import mill.javalib.api.JvmWorkerApi as PublicJvmWorkerApi
import mill.javalib.api.internal.JvmWorkerApi
import mill.api.daemon.internal.{CompileProblemReporter, KotlinModuleApi, internal}
import mill.javalib.{JavaModule, JvmWorkerModule, Lib}
import mill.util.{Jvm, Version}
import mill.*

import java.io.File
import mainargs.Flag
import mill.api.daemon.internal.bsp.{BspBuildTarget, BspModuleApi}
import mill.javalib.api.internal.{JavaCompilerOptions, ZincCompileJava}

/**
 * Core configuration required to compile a single Kotlin module
 */
trait KotlinModule extends JavaModule with KotlinModuleApi { outer =>

  /**
   * The Kotlin version to be used (for API and Language level settings).
   */
  def kotlinVersion: T[String]

  /**
   * The compiler language version. Default is derived from [[kotlinVersion]].
   */
  def kotlinLanguageVersion: T[String] = Task { kotlinVersion().split("[.]").take(2).mkString(".") }

  /**
   * The compiler API version. Default is derived from [[kotlinLanguageVersion]],
   * as the value typically can not be greater than [[kotlinLanguageVersion]].
   */
  def kotlinApiVersion: T[String] = Task { kotlinLanguageVersion() }

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
   * The dependencies of this module.
   * Defaults to add the kotlin-stdlib dependency matching the [[kotlinVersion]].
   */
  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    super.mandatoryMvnDeps() ++ Seq(
      mvn"org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion()}"
    )
  }

  /**
   * Flag to use explicit API check in the compiler. Default is `false`.
   */
  def kotlinExplicitApi: T[Boolean] = Task { false }

  protected def jvmWorkerRef: ModuleRef[JvmWorkerModule] = jvmWorker

  override def checkGradleModules: T[Boolean] = true
  override def resolutionParams: Task[ResolutionParams] = Task.Anon {
    super.resolutionParams().addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" -> VariantMatcher.Equals("jvm")
    )
  }

  /**
   * The Java classpath resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerMvnDeps]].
   */
  def kotlinCompilerClasspath: T[Seq[PathRef]] = Task {
    val deps = kotlinCompilerMvnDeps() ++ Seq(
      Dep.millProjectModule("mill-libs-kotlinlib-worker")
    )
    defaultResolver().classpath(
      deps,
      resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
    )
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
  def kotlinCompilerMvnDeps: T[Seq[Dep]] = Task {
    val useEmbeddable = kotlinUseEmbeddableCompiler()
    val kv = kotlinVersion()
    val isOldKotlin = Seq("1.0.", "1.1.", "1.2.0", "1.2.1", "1.2.2", "1.2.3", "1.2.4")
      .exists(prefix => kv.startsWith(prefix))

    val compilerDep =
      if (useEmbeddable) mvn"org.jetbrains.kotlin:kotlin-compiler-embeddable:${kv}"
      else mvn"org.jetbrains.kotlin:kotlin-compiler:${kv}"

    val btApiDeps = when(kotlincUseBtApi())(
      mvn"org.jetbrains.kotlin:kotlin-build-tools-api:$kv",
      mvn"org.jetbrains.kotlin:kotlin-build-tools-impl:$kv"
    )

    val scriptCompilerDeps: Seq[Dep] = when(!isOldKotlin)(
      (if (useEmbeddable) Seq(
         mvn"org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${kv}"
       )
       else Seq(
         mvn"org.jetbrains.kotlin:kotlin-scripting-compiler:${kv}",
         mvn"org.jetbrains.kotlin:kotlin-scripting-compiler-impl:${kv}",
         mvn"org.jetbrains.kotlin:kotlin-scripting-jvm:$kv"
       ))*
    )

    Seq(compilerDep) ++ btApiDeps ++ scriptCompilerDeps
  }

  /**
   * Compiler Plugin dependencies.
   */
  def kotlincPluginMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * The resolved plugin jars
   */
  def kotlincPluginJars: T[Seq[PathRef]] = Task {
    val jars = defaultResolver().classpath(
      kotlincPluginMvnDeps()
        // Don't resolve transitive jars
        .map(d => d.exclude("*" -> "*")),
      resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
    )
    jars.toSeq
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
   * The generated documentation, containing all the Dokka HTML files, for
   * publishing to Maven Central. You can control Dokka version by using [[dokkaVersion]]
   * and option by using [[dokkaOptions]].
   */
  def dokkaGenerated: T[PathRef] = Task[PathRef] {
    val dokkaDir = Task.dest / "dokka"
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

      os.call(
        cmd = (
          Jvm.javaExe(javaHome().map(_.path)),
          "-jar",
          dokkaCliClasspath().head.path.toString(),
          options
        ),
        stdin = os.Inherit,
        stdout = os.Inherit
      )
    }

    PathRef(dokkaDir)
  }

  /**
   * The documentation jar, containing all the Dokka HTML files, for
   * publishing to Maven Central. You can control Dokka version by using [[dokkaVersion]]
   * and option by using [[dokkaOptions]].
   */
  override def docJar: T[PathRef] = Task[PathRef] {
    PathRef(Jvm.createJar(Task.dest / "out.jar", Seq(dokkaGenerated().path)))
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
        mvn"org.jetbrains.dokka:dokka-cli:${dokkaVersion()}"
      ),
      resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
    )
  }

  private def dokkaPluginsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        mvn"org.jetbrains.dokka:dokka-base:${dokkaVersion()}",
        mvn"org.jetbrains.dokka:analysis-kotlin-descriptors:${dokkaVersion()}",
        Dep.parse(Versions.kotlinxHtmlJvmDep),
        Dep.parse(Versions.freemarkerDep)
      ),
      resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
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
          worker = jvmWorkerRef().internalWorker(),
          upstreamCompileOutput = updateCompileOutput,
          javaSourceFiles = javaSourceFiles,
          compileCp = compileCp,
          javaHome = javaHome().map(_.path),
          javacOptions = javacOptions(),
          compileProblemReporter = ctx.reporter(hashCode),
          reportOldProblems = internalReportOldProblems()
        )
      }

      if (isMixed || isKotlin) {
        val extra = if (isJava) s"and reading ${javaSourceFiles.size} Java sources " else ""
        ctx.log.info(
          s"Compiling ${kotlinSourceFiles.size} Kotlin sources ${extra}to ${classes} ..."
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
          extraKotlinArgs
        ).flatten

        val workerResult =
          KotlinWorkerManager.kotlinWorker().withValue(kotlinCompilerClasspath()) {
            _.compile(
              target = KotlinWorkerTarget.Jvm,
              useBtApi = kotlincUseBtApi(),
              args = compilerArgs,
              sources = kotlinSourceFiles ++ javaSourceFiles
            )
          }

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
   * Enable use of new Kotlin Build API (Beta).
   * Enabled by default for Kotlin 2.x targetting the JVM.
   */
  def kotlincUseBtApi: T[Boolean] = Task {
    Version.parse(kotlinVersion())
      .isNewerThan(Version.parse("2.0.0"))(using Version.IgnoreQualifierOrdering)
  }

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
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      compileProblemReporter: Option[CompileProblemReporter],
      reportOldProblems: Boolean
  )(implicit ctx: PublicJvmWorkerApi.Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions(javacOptions)
    worker.compileJava(
      ZincCompileJava(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = javaSourceFiles,
        compileClasspath = compileCp,
        javacOptions = jOpts.compiler,
        incrementalCompilation = true
      ),
      javaHome = javaHome,
      javaRuntimeOptions = jOpts.runtime,
      reporter = compileProblemReporter,
      reportCachedProblems = reportOldProblems
    )
  }

  private[kotlinlib] def internalReportOldProblems: Task[Boolean] = zincReportCachedProblems

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(
      BspModuleApi.LanguageId.Java,
      BspModuleApi.LanguageId.Kotlin
    ),
    canCompile = true,
    canRun = true
  )

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++
        kotlinCompilerClasspath() ++
        kotlinCompilerClasspath() ++
        dokkaCliClasspath() ++
        dokkaPluginsClasspath()
    ).distinct
  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KotlinTests extends KotlinModule.Tests{
    def outerRef = ModuleRef(KotlinModule.this)
  }

}

object KotlinModule {
  trait Tests extends JavaModule.Tests with KotlinModule {
    def outerRef: ModuleRef[KotlinModule]

    override def kotlinLanguageVersion: T[String] = outerRef().kotlinLanguageVersion()
    override def kotlinApiVersion: T[String] = outerRef().kotlinApiVersion()
    override def kotlinExplicitApi: T[Boolean] = false
    override def kotlinVersion: T[String] = Task { outerRef().kotlinVersion() }
    override def kotlincPluginMvnDeps: T[Seq[Dep]] =
      Task { outerRef().kotlincPluginMvnDeps() }
      // TODO: make Xfriend-path an explicit setting
    override def kotlincOptions: T[Seq[String]] = Task {
      outerRef().kotlincOptions().filterNot(_.startsWith("-Xcommon-sources")) ++
        Seq(s"-Xfriend-paths=${outerRef().compile().classes.path.toString()}")
    }
    override def kotlinUseEmbeddableCompiler: Task[Boolean] =
      Task.Anon { outerRef().kotlinUseEmbeddableCompiler() }
    override def kotlincUseBtApi: Task.Simple[Boolean] = Task { outerRef().kotlincUseBtApi() }
  }
  private[mill] def addJvmVariantAttributes: ResolutionParams => ResolutionParams = { params =>
    params.addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" -> VariantMatcher.Equals("jvm"),
      "org.gradle.jvm.environment" -> VariantMatcher.Equals("standard-jvm")
    )
  }

}
