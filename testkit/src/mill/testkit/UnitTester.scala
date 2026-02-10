package mill.testkit

import mill.Task
import mill.api.{BuildCtx, DummyInputStream, ExecResult, Result, SystemStreams, Val}
import mill.api.ExecResult.OuterStack
import mill.constants.OutFiles.OutFiles.millChromeProfile
import mill.constants.OutFiles.OutFiles.millProfile
import mill.api.Evaluator
import mill.api.SelectMode
import mill.constants.EnvVars
import mill.internal.JsonArrayLogger
import mill.launcher.DaemonRpc

import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.ThreadPoolExecutor
import scala.annotation.targetName

object UnitTester {
  case class Result[T](value: T, evalCount: Int)

  def apply(
      module: mill.testkit.TestRootModule,
      sourceRoot: os.Path,
      failFast: Boolean = false,
      threads: Option[Int] = Some(1),
      outStream: PrintStream = Console.out,
      errStream: PrintStream = Console.err,
      inStream: InputStream = DummyInputStream,
      debugEnabled: Boolean = false,
      env: Map[String, String] = Evaluator.defaultEnv,
      resetSourcePath: Boolean = true,
      offline: Boolean = false
  ) = new UnitTester(
    module = module,
    sourceRoot = Option(sourceRoot),
    failFast = failFast,
    threads = threads,
    outStream = outStream,
    errStream = errStream,
    inStream = inStream,
    debugEnabled = debugEnabled,
    env = env,
    resetSourcePath = resetSourcePath,
    offline = offline
  )
}

/**
 * @param module The module under test
 * @param failFast failFast mode enabled
 * @param threads explicitly used nr. of parallel threads
 */
class UnitTester(
    module: mill.testkit.TestRootModule,
    sourceRoot: Option[os.Path],
    resetSourcePath: Boolean,
    failFast: Boolean,
    threads: Option[Int],
    outStream: PrintStream,
    errStream: PrintStream,
    inStream: InputStream,
    debugEnabled: Boolean,
    env: Map[String, String],
    offline: Boolean
)(using fullName: sourcecode.FullName) extends AutoCloseable {
  assert(
    mill.api.MillURLClassLoader.openClassloaders.isEmpty,
    s"Unit tester detected leaked classloaders on initialization: \n${mill.api.MillURLClassLoader.openClassloaders.mkString("\n")}"
  )
  val outPath: os.Path = resolveAliasedPath(module.moduleDir) / "out"

  if (resetSourcePath) {
    mill.util.Retry() { // Retry because this is flaky on windows due to file locking
      os.remove.all(resolveAliasedPath(module.moduleDir))
    }
    os.makeDir.all(resolveAliasedPath(module.moduleDir))

    for (sourceFileRoot <- sourceRoot) {
      os.copy.over(sourceFileRoot, resolveAliasedPath(module.moduleDir), createFolders = true)
    }
  } else {
    sourceRoot match {
      case Some(sourceRoot) =>
        throw new IllegalArgumentException(
          s"Cannot provide sourceRoot=$sourceRoot when resetSourcePath=false"
        )
      case None => // ok
    }
  }

  object logger extends mill.internal.PromptLogger(
        colored = true,
        enableTicker = false,
        infoColor = mill.internal.Colors.Default.info,
        warnColor = mill.internal.Colors.Default.warn,
        errorColor = mill.internal.Colors.Default.error,
        successColor = mill.internal.Colors.Default.success,
        highlightColor = mill.internal.Colors.Default.highlight,
        systemStreams0 = new SystemStreams(out = outStream, err = errStream, in = inStream),
        debugEnabled = debugEnabled,
        titleText = "",
        terminalDimsCallback = () => None,
        currentTimeMillis = () => System.currentTimeMillis(),
        chromeProfileLogger = new JsonArrayLogger.ChromeProfile(outPath / millChromeProfile)
      ) {
    val prefix: String = {
      val idx = fullName.value.lastIndexOf(".")
      if (idx > 0) fullName.value.substring(0, idx)
      else fullName.value
    }
    override def error(s: String): Unit = super.error(s"${prefix}: ${s}")
    override def warn(s: String): Unit = super.warn(s"${prefix}: ${s}")
    override def info(s: String): Unit = super.info(s"${prefix}: ${s}")
    override def debug(s: String): Unit = super.debug(s"${prefix}: ${s}")
    override def ticker(s: String): Unit = super.ticker(s"${prefix}: ${s}")
  }

  val effectiveThreadCount: Int =
    threads.getOrElse(Runtime.getRuntime().availableProcessors())
  val ec: Option[ThreadPoolExecutor] =
    if (effectiveThreadCount == 1) None
    else Some(mill.exec.ExecutionContexts.createExecutor(effectiveThreadCount))

  private def resolveAliasedPath(path: os.Path): os.Path = {
    val nio = path.wrapped
    if (nio.isAbsolute) os.Path(nio.toAbsolutePath.normalize())
    else {
      val raw = nio.toString.replace('\\', '/')
      val workspaceAlias = "out/mill-workspace"
      val homeAlias = "out/mill-home"
      val workspaceRoot = BuildCtx.workspaceRoot

      def resolveFromAlias(base: os.Path, aliasIdx: Int, alias: String): os.Path = {
        val suffix = raw.substring(aliasIdx + alias.length).stripPrefix("/")
        if (suffix.isEmpty) base else base / os.RelPath(suffix)
      }

      if (raw == workspaceAlias) workspaceRoot
      else if (raw.startsWith(workspaceAlias + "/"))
        workspaceRoot / os.RelPath(raw.stripPrefix(workspaceAlias + "/"))
      else if (raw == homeAlias) os.home
      else if (raw.startsWith(homeAlias + "/"))
        os.home / os.RelPath(raw.stripPrefix(homeAlias + "/"))
      else {
        val workspaceIdx = raw.indexOf(workspaceAlias)
        if (workspaceIdx >= 0) resolveFromAlias(workspaceRoot, workspaceIdx, workspaceAlias)
        else {
          val homeIdx = raw.indexOf(homeAlias)
          if (homeIdx >= 0) resolveFromAlias(os.home, homeIdx, homeAlias)
          else os.Path(raw, os.pwd)
        }
      }
    }
  }

  private val normalizedModuleDir = resolveAliasedPath(module.moduleDir)
  private val workspaceAbs = normalizedModuleDir.wrapped.toAbsolutePath.normalize().toString
  private val homeAbs = os.home.wrapped.toAbsolutePath.normalize().toString
  private val relativizerBase = s"$workspaceAbs,out/mill-workspace;$homeAbs,out/mill-home"
  private def ensureRelativizerAliases(base: os.Path, workspaceRoot: os.Path): Unit = {
    def linkExists(link: os.Path): Boolean =
      java.nio.file.Files.exists(link.toNIO, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    def ensureSymlink(link: os.Path, dest: os.Path): Unit = {
      val destAbs = dest.wrapped.toAbsolutePath.normalize()
      if (!linkExists(link)) {
        try java.nio.file.Files.createSymbolicLink(link.toNIO, destAbs)
        catch {
          case e: java.nio.file.NoSuchFileException =>
            throw new IllegalStateException(
              s"UnitTester.ensureSymlink failed for link=$link base=$base moduleDir=${module.moduleDir} normalizedModuleDir=$normalizedModuleDir pwd=${os.pwd}",
              e
            )
          case _: java.nio.file.FileAlreadyExistsException =>
            if (!linkExists(link)) throw new java.nio.file.FileAlreadyExistsException(link.toString)
        }
      }
    }

    val out = base / "out"
    val workspaceAlias = out / "mill-workspace"
    val homeAlias = out / "mill-home"
    os.makeDir.all(out)
    ensureSymlink(workspaceAlias, workspaceRoot)
    ensureSymlink(homeAlias, os.home)
  }
  ensureRelativizerAliases(normalizedModuleDir, normalizedModuleDir)
  ensureRelativizerAliases(os.pwd, normalizedModuleDir)
  private val effectiveEnv = env ++ Map(
    EnvVars.MILL_WORKSPACE_ROOT -> workspaceAbs,
    EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> relativizerBase
  )

  val execution = new mill.exec.Execution(
    baseLogger = new mill.internal.PrefixLogger(logger, Nil),
    profileLogger = new mill.internal.JsonArrayLogger.Profile(outPath / millProfile),
    workspace = normalizedModuleDir,
    outPath = outPath,
    externalOutPath = outPath,
    rootModule = module,
    classLoaderSigHash = 0,
    classLoaderIdentityHash = 0,
    workerCache = collection.mutable.Map.empty,
    env = effectiveEnv,
    failFast = failFast,
    ec = ec,
    codeSignatures = Map(),
    systemExit = (reason, exitCode) =>
      throw Exception(s"systemExit called: reason=$reason, exitCode=$exitCode"),
    exclusiveSystemStreams = new SystemStreams(outStream, errStream, inStream),
    getEvaluator = () => evaluator,
    offline = offline,
    useFileLocks = false,
    enableTicker = false,
    staticBuildOverrideFiles = Map(),
    depth = 0,
    isFinalDepth = true,
    spanningInvalidationTree = None
  )

  val evaluator: Evaluator = new mill.eval.EvaluatorImpl(
    allowPositionalCommandArgs = false,
    selectiveExecution = false,
    execution = execution
  )

  def apply(args: String*): Either[ExecResult.Failing[?], UnitTester.Result[Seq[?]]] = {
    Evaluator.withCurrentEvaluator(evaluator) {
      evaluator.resolveTasks(args, SelectMode.Separated)
    } match {
      case f: Result.Failure => Left(ExecResult.Failure(f.error))
      case Result.Success(resolved) => apply(resolved)
    }
  }

  def apply[T](task: Task[T]): Either[ExecResult.Failing[T], UnitTester.Result[T]] = {
    apply(Seq(task)) match {
      case Left(f) => Left(f.asInstanceOf[ExecResult.Failing[T]])
      case Right(UnitTester.Result(Seq(v), i)) =>
        Right(UnitTester.Result(v.asInstanceOf[T], i))
      case _ => ???
    }
  }

  @targetName("applyTasks")
  def apply(
      tasks: Seq[Task[?]]
  ): Either[ExecResult.Failing[?], UnitTester.Result[Seq[?]]] = {

    val evaluated = evaluator.execute(tasks.asInstanceOf[Seq[Task[Any]]]).executionResults

    if (evaluated.transitiveFailing.nonEmpty) Left(evaluated.transitiveFailing.values.head)
    else {
      val values = evaluated.results.map(_.asInstanceOf[ExecResult.Success[Val]].value.value)
      val evalCount = evaluated
        .uncached
        .collect {
          case t: Task.Computed[_]
              if module.moduleInternal.simpleTasks.contains(t)
                && !t.ctx.external => t
          case t: Task.Command[_] => t
        }
        .size

      Right(UnitTester.Result(values, evalCount))
    }

  }

  def fail(
      task: Task.Simple[?],
      expectedFailCount: Int,
      expectedRawValues: Seq[ExecResult[?]]
  ): Unit = {

    val res = evaluator.execute(Seq(task)).executionResults

    val cleaned = res.results.map {
      case ExecResult.Exception(ex, _) => ExecResult.Exception(ex, new OuterStack(Nil))
      case x => x.map(_.value)
    }

    assert(cleaned == expectedRawValues)
    assert(res.transitiveFailing.size == expectedFailCount)

  }

  def check(tasks: Seq[Task[?]], expected: Seq[Task[?]]): Unit = {

    val evaluated = evaluator.execute(tasks.asInstanceOf[Seq[Task[Any]]]).executionResults
      .uncached
      .flatMap(_.asSimple)
      .filter(module.moduleInternal.simpleTasks.contains)
      .filter(!_.isInstanceOf[Task.Input[?]])
    assert(
      evaluated.toSet == expected.toSet,
      s"evaluated is not equal expected. evaluated=${evaluated}, expected=${expected}"
    )
  }

  /** Replaces the [[BuildCtx.workspaceRoot]] for the given scope with [[module.moduleDir]]. */
  def scoped[T](tester: UnitTester => T): T = {
    try {
      BuildCtx.workspaceRoot0.withValue(module.moduleDir) {
        mill.api.daemon.LauncherSubprocess.withValue(config =>
          DaemonRpc
            .defaultRunSubprocessWithStreams(None)(DaemonRpc.ServerToClient.RunSubprocess(config))
            .exitCode
        ) {
          tester(this)
        }
      }
    } finally close()
  }

  def closeWithoutCheckingLeaks(): Unit = {
    for (case (_, Val(obsolete: AutoCloseable), _) <- evaluator.workerCache.values) {
      obsolete.close()
    }
    evaluator.close()
  }

  def close(): Unit = {
    closeWithoutCheckingLeaks()
    checkLeaks()
  }
  def checkLeaks() = {
    assert(
      mill.api.MillURLClassLoader.openClassloaders.isEmpty,
      s"Unit tester detected leaked classloaders on close: \n${mill.api.MillURLClassLoader.openClassloaders.mkString("\n")}"
    )
  }
}
