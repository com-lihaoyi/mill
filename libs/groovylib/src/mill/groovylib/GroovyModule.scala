package mill
package groovylib

import mill.api.{ModuleRef, Result}
import mill.javalib.api.CompilationResult
import mill.javalib.api.JvmWorkerApi as PublicJvmWorkerApi
import mill.api.daemon.internal.{CompileProblemReporter, GroovyModuleApi, internal}
import mill.javalib.{Dep, JavaModule, JvmWorkerModule, Lib}
import mill.*
import mainargs.Flag
import mill.api.daemon.internal.bsp.{BspBuildTarget, BspModuleApi}
import mill.javalib.api.internal.{JavaCompilerOptions, JvmWorkerApi, ZincCompileJava}

/**
 * Core configuration required to compile a single Groovy module
 */
trait GroovyModule extends JavaModule with GroovyModuleApi { outer =>

  /**
   * The Groovy version to be used.
   */
  def groovyVersion: T[String]

  /**
   * The compiler language version. Default is derived from [[groovyVersion]].
   */
  def groovyLanguageVersion: T[String] = Task { groovyVersion().split("[.]").take(2).mkString(".") }

  override def bomMvnDeps: T[Seq[Dep]] = super.bomMvnDeps() ++ Seq(
    mvn"org.apache.groovy:groovy-bom:${groovyVersion()}"
  )

  /**
   * All individual source files fed into the compiler.
   */
  override def allSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("groovy", "java")).map(PathRef(_))
  }

  /**
   * All individual Java source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  private def allJavaSourceFiles: T[Seq[PathRef]] = Task {
    allSourceFiles().filter(_.path.ext.toLowerCase() == "java")
  }

  /**
   * All individual Groovy source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  private def allGroovySourceFiles: T[Seq[PathRef]] = Task {
    allSourceFiles().filter(path => Seq("groovy").contains(path.path.ext.toLowerCase()))
  }

  /**
   * The dependencies of this module.
   * Defaults to add the groovy dependency matching the [[groovyVersion]].
   */
  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    super.mandatoryMvnDeps() ++ Seq(
      mvn"org.apache.groovy:groovy:${groovyVersion()}"
    )
  }

  private def jvmWorkerRef: ModuleRef[JvmWorkerModule] = jvmWorker

  override def checkGradleModules: T[Boolean] = true

  /**
   * The Java classpath resembling the Groovy compiler.
   * Default is derived from [[groovyCompilerMvnDeps]].
   */
  def groovyCompilerClasspath: T[Seq[PathRef]] = Task {
    val deps = groovyCompilerMvnDeps() ++ Seq(
      Dep.millProjectModule("mill-libs-groovylib-worker")
    )
    defaultResolver().classpath(
      deps,
      resolutionParamsMapOpt = None
    )
  }

  /**
   * The Ivy/Coursier dependencies resembling the Groovy compiler.
   *
   * Default is derived from [[groovyCompilerVersion]].
   */
  def groovyCompilerMvnDeps: T[Seq[Dep]] = Task {
    val gv = groovyVersion()

    val compilerDep = mvn"org.apache.groovy:groovy:$gv"

    Seq(compilerDep)
  }

  /**
   * Compiler Plugin dependencies.
   */
  def groovyCompilerPluginMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * The resolved plugin jars
   */
  def groovyCompilerPluginJars: T[Seq[PathRef]] = Task {
    val jars = defaultResolver().classpath(
      groovycPluginMvnDeps()
        // Don't resolve transitive jars
        .map(d => d.exclude("*" -> "*")),
      resolutionParamsMapOpt = None
    )
    jars.toSeq
  }

  /**
   * Compiles all the sources to JVM class files.
   */
  override def compile: T[CompilationResult] = Task {
    groovyCompileTask()()
  }

  /**
   * The actual Groovy compile task (used by [[compile]] and [[groovycHelp]]).
   */
  protected def groovyCompileTask(): Task[CompilationResult] =
    Task.Anon {
      val ctx = Task.ctx()
      val dest = ctx.dest
      val classes = dest / "classes"
      os.makeDir.all(classes)

      val javaSourceFiles = allJavaSourceFiles().map(_.path)
      val groovySourceFiles = allGroovySourceFiles().map(_.path)

      val isGroovy = groovySourceFiles.nonEmpty
      val isJava = javaSourceFiles.nonEmpty
      val isMixed = isGroovy && isJava

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

      if (isMixed || isGroovy) {
        ctx.log.info(
          s"Compiling ${groovySourceFiles.size} Groovy sources to ${classes} ..."
        )

        val compileCp = compileClasspath().map(_.path).filter(os.exists)

        val workerResult =
          GroovyWorkerManager.groovyWorker().withValue(groovyCompilerClasspath()) {
            _.compile(groovySourceFiles, compileCp, classes)
          }

        val analysisFile = dest / "groovy.analysis.dummy" // needed for mills CompilationResult
        os.write(target = analysisFile, data = "", createFolders = true)

        workerResult match {
          case Result.Success(_) =>
            val cr = CompilationResult(analysisFile, PathRef(classes))
            if (!isJava) {
              // pure Groovy project
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
   * Additional Groovy compiler options to be used by [[compile]].
   */
  def groovycOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Aggregation of all the options passed to the Groovy compiler.
   * In most cases, instead of overriding this target you want to override `groovycOptions` instead.
   */
  def allGroovycOptions: T[Seq[String]] = Task {
    groovycOptions()
  }

  private[groovylib] def internalCompileJavaFiles(
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

  private[groovylib] def internalReportOldProblems: Task[Boolean] = zincReportCachedProblems

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(
      BspModuleApi.LanguageId.Java,
      BspModuleApi.LanguageId.Groovy
    ),
    canCompile = true,
    canRun = true
  )

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++
        groovyCompilerClasspath()
    ).distinct
  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait GroovyTests extends JavaTests with GroovyModule {

    override def groovyLanguageVersion: T[String] = outer.groovyLanguageVersion()
    override def groovyVersion: T[String] = Task { outer.groovyVersion() }
    override def groovycPluginMvnDeps: T[Seq[Dep]] =
      Task { outer.groovycPluginMvnDeps() }
      // TODO: make Xfriend-path an explicit setting
    override def groovycOptions: T[Seq[String]] = Task {
      // FIXME
      outer.groovycOptions().filterNot(_.startsWith("-Xcommon-sources")) ++
        Seq(s"-Xfriend-paths=${outer.compile().classes.path.toString()}")
    }
  }

}

// TODO maybe an StandaloneGroovyTestsModule
