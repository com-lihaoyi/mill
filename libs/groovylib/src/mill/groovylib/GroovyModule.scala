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
import mill.util.Version

/**
 * Core configuration required to compile a single Groovy module.
 *
 * Resolves
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

  private def isGroovyBomAvailable: T[Boolean] = Task {
    if (groovyVersion().isBlank) {
      false
    } else {
      Version.isAtLeast(groovyVersion(), "4.0.26")(using Version.IgnoreQualifierOrdering)
    }
  }

  override def bomMvnDeps: T[Seq[Dep]] = super.bomMvnDeps() ++
    Seq(groovyVersion())
      .filter(_.nonEmpty && isGroovyBomAvailable())
      .map(v => mvn"org.apache.groovy:groovy-bom:$v")

  /**
   * All individual source files fed into the compiler.
   */
  override def allSourceFiles: T[Seq[PathRef]] = Task {
    allGroovySourceFiles() ++ allJavaSourceFiles()
  }

  /**
   * All individual Java source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  private def allJavaSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("java")).map(PathRef(_))
  }

  /**
   * All individual Groovy source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  private def allGroovySourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("groovy")).map(PathRef(_))
  }

  /**
   * The dependencies of this module.
   * Defaults to add the Groovy dependency matching the [[groovyVersion]].
   */
  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    super.mandatoryMvnDeps() ++ groovyCompilerMvnDeps()
  }

  def jvmWorkerRef: ModuleRef[JvmWorkerModule] = jvmWorker

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
   * Default is derived from [[groovyVersion]].
   */
  def groovyCompilerMvnDeps: T[Seq[Dep]] = Task {
    val gv = groovyVersion()
    Seq(mvn"org.apache.groovy:groovy:$gv")
  }

  /**
   * Compiles all the sources to JVM class files.
   */
  override def compile: T[CompilationResult] = Task {
    groovyCompileTask()()
  }

  /**
   * The actual Groovy compile task (used by [[compile]]).
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
          s"Compiling ${javaSourceFiles.size} Java sources to $classes ..."
        )
        // The compiler step is lazy, but its dependencies are not!
        internalCompileJavaFiles(
          worker = jvmWorkerRef().internalWorker(),
          upstreamCompileOutput = updateCompileOutput,
          javaSourceFiles = javaSourceFiles,
          compileCp = compileCp,
          javaHome = javaHome().map(_.path),
          javacOptions = javacOptions(),
          compileProblemReporter = ctx.reporter(hashCode),
          reportOldProblems = zincReportCachedProblems()
        )
      }

      if (isMixed) {
        ctx.log.info("Compiling Groovy stubs for mixed compilation")

        val workerStubResult =
          GroovyWorkerManager.groovyWorker().withValue(groovyCompilerClasspath()) {
            _.compileGroovyStubs(groovySourceFiles, compileCp, classes)
          }
        workerStubResult match {
          case Result.Success(_) => compileJava
          case Result.Failure(reason) => Result.Failure(reason)
        }
      }

      if (isMixed || isGroovy) {
        ctx.log.info(
          s"Compiling ${groovySourceFiles.size} Groovy sources to $classes ..."
        )

        val workerGroovyResult =
          GroovyWorkerManager.groovyWorker().withValue(groovyCompilerClasspath()) {
            _.compile(groovySourceFiles, compileCp, classes)
          }

        // TODO figure out if there is a better way to do this
        val analysisFile = dest / "groovy.analysis.dummy" // needed for mills CompilationResult
        os.write(target = analysisFile, data = "", createFolders = true)

        workerGroovyResult match {
          case Result.Success(_) =>
            CompilationResult(analysisFile, PathRef(classes))
          case Result.Failure(reason) => Result.Failure(reason)
        }
      } else {
        compileJava
      }
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
   * A test submodule linked to its parent module best suited for unit-tests.
   */
  trait GroovyTests extends JavaTests with GroovyModule {

    override def groovyLanguageVersion: T[String] = outer.groovyLanguageVersion()
    override def groovyVersion: T[String] = Task { outer.groovyVersion() }
    override def bomMvnDeps: T[Seq[Dep]] = outer.bomMvnDeps()
    override def mandatoryMvnDeps: Task.Simple[Seq[Dep]] = outer.mandatoryMvnDeps
  }
}
