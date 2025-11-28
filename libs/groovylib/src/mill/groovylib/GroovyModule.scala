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
import mill.groovylib.worker.api.GroovyCompilerConfiguration
import mill.javalib.api.internal.{InternalJvmWorkerApi, JavaCompilerOptions, ZincOp}
import mill.util.Version

/**
 * Core configuration required to compile a single Groovy module.
 *
 * Resolves
 */
@mill.api.experimental
trait GroovyModule extends JavaModule with GroovyModuleApi { outer =>

  /**
   * The Groovy version to be used.
   */
  def groovyVersion: T[String]

  /**
   * The compiler language version. Default is derived from [[groovyVersion]].
   */
  def groovyLanguageVersion: T[String] = Task { groovyVersion().split("[.]").take(2).mkString(".") }

  private def useGroovyBom: T[Boolean] = Task {
    if (groovyVersion().isBlank) {
      false
    } else {
      Version.isAtLeast(groovyVersion(), "4.0.26")(using Version.IgnoreQualifierOrdering)
    }
  }

  override def bomMvnDeps: T[Seq[Dep]] = super.bomMvnDeps() ++
    Seq(groovyVersion())
      .filter(_.nonEmpty && useGroovyBom())
      .map(v => mvn"org.apache.groovy:groovy-bom:$v")

  /**
   * All individual source files fed into the compiler.
   */
  override def allSourceFiles: T[Seq[PathRef]] = Task {
    allGroovySourceFiles() ++ allJavaSourceFiles()
  }

  /**
   * Specifiy the bytecode version for the Groovy compiler.
   * {{{
   *   def groovyCompileTargetBytecode = Some("17")
   * }}}
   */
  def groovyCompileTargetBytecode: T[Option[String]] = None

  /**
   * Specify if the Groovy compiler should enable preview features.
   */
  def groovyCompileEnablePreview: T[Boolean] = false

  /**
   * Specify which global AST transformations should be disabled. Be aware that transformations
   * like [[groovy.transform.Immutable]] are so-called "local" transformations and will not be
   * affected.
   *
   * see [[https://docs.groovy-lang.org/latest/html/api/org/codehaus/groovy/control/CompilerConfiguration.html#setDisabledGlobalASTTransformations(java.util.Set) Groovy-Docs]]
   */
  def disabledGlobalAstTransformations: T[Set[String]] = Set.empty

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
   * The Coursier dependencies resembling the Groovy compiler.
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

  def compileGroovyStubs: T[Result[Unit]] = Task(persistent = true) {
    val groovySourceFiles = allGroovySourceFiles().map(_.path)
    val stubDir = compileGeneratedGroovyStubs()
    Task.ctx().log.info(
      s"Generating Java stubs for ${groovySourceFiles.size} Groovy sources to $stubDir ..."
    )

    val compileCp = compileClasspath().map(_.path).filter(os.exists)
    val config = GroovyCompilerConfiguration(
      enablePreview = groovyCompileEnablePreview(),
      targetBytecode = groovyCompileTargetBytecode(),
      disabledGlobalAstTransformations = disabledGlobalAstTransformations()
    )

    GroovyWorkerManager.groovyWorker().withValue(groovyCompilerClasspath()) {
      _.compileGroovyStubs(groovySourceFiles, compileCp, stubDir, config)
    }
  }

  /**
   * Path to Java stub sources as part of the `compile` step. Stubs are generated
   * by the Groovy compiler and later used by the Java compiler.
   */
  def compileGeneratedGroovyStubs: T[os.Path] = Task(persistent = true) { Task.dest }

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
      val compileCp = compileClasspath().map(_.path).filter(os.exists)
      val updateCompileOutput = upstreamCompileOutput()

      sealed trait CompilationStrategy
      case object JavaOnly extends CompilationStrategy
      case object GroovyOnly extends CompilationStrategy
      case object Mixed extends CompilationStrategy

      val strategy: CompilationStrategy = (isJava, isGroovy) match {
        case (true, false) => JavaOnly
        case (false, true) => GroovyOnly
        case (true, true) => Mixed
        case (false, false) => JavaOnly // fallback, though this shouldn't happen
      }

      val config = GroovyCompilerConfiguration(
        enablePreview = groovyCompileEnablePreview(),
        targetBytecode = groovyCompileTargetBytecode(),
        disabledGlobalAstTransformations = disabledGlobalAstTransformations()
      )

      def compileJava: Result[CompilationResult] = {
        ctx.log.info(
          s"Compiling ${javaSourceFiles.size} Java sources to $classes ..."
        )
        // The compiler step is lazy, but its dependencies are not!
        internalCompileJavaFiles(
          worker = jvmWorkerRef().internalWorker(),
          upstreamCompileOutput = updateCompileOutput,
          javaSourceFiles = javaSourceFiles,
          compileCp = compileCp :+ compileGeneratedGroovyStubs(),
          javaHome = javaHome().map(_.path),
          javacOptions = javacOptions(),
          compileProblemReporter = ctx.reporter(hashCode),
          reportOldProblems = zincReportCachedProblems()
        )
      }

      def compileGroovy(): Result[CompilationResult] = {
        ctx.log.info(
          s"Compiling ${groovySourceFiles.size} Groovy sources to $classes ..."
        )
        val workerGroovyResult =
          GroovyWorkerManager.groovyWorker().withValue(groovyCompilerClasspath()) {
            _.compile(groovySourceFiles, compileCp, classes, config)
          }

        // TODO figure out if there is a better way to do this
        val analysisFile = dest / "groovy.analysis.dummy" // needed for Mills CompilationResult
        os.write(target = analysisFile, data = "", createFolders = true)

        workerGroovyResult match {
          case Result.Success(_) =>
            CompilationResult(analysisFile, PathRef(classes))
          case Result.Failure(reason) => Result.Failure(reason)
        }
      }

      strategy match {
        case JavaOnly =>
          compileJava

        case GroovyOnly =>
          compileGroovy()

        case Mixed =>
          compileGroovyStubs()
            .flatMap(_ => compileJava)
            .flatMap(_ => compileGroovy())
      }

    }

  private[groovylib] def internalCompileJavaFiles(
      worker: InternalJvmWorkerApi,
      upstreamCompileOutput: Seq[CompilationResult],
      javaSourceFiles: Seq[os.Path],
      compileCp: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      compileProblemReporter: Option[CompileProblemReporter],
      reportOldProblems: Boolean
  )(implicit ctx: PublicJvmWorkerApi.Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions.split(javacOptions)
    worker.apply(
      ZincOp.CompileJava(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = javaSourceFiles,
        compileClasspath = compileCp,
        javacOptions = jOpts.compiler,
        incrementalCompilation = true,
        workDir = ctx.dest
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

@mill.api.experimental
object GroovyModule {
  // Keep in sync with GroovyModule#KotlinTests, duplicated due to binary compatibility concerns
  trait GroovyTests0 extends JavaModule.JavaTests0 with GroovyModule {
    private val outer: GroovyModule = moduleDeps.head.asInstanceOf[GroovyModule]
    override def groovyLanguageVersion: T[String] = outer.groovyLanguageVersion()
  }
}
