package mill.testkit

import mill.Task
import mill.api.{BuildCtx, DummyInputStream, Evaluator, ExecResult, PathRef, Result, SelectMode, SystemStreams, Val}
import mill.api.ExecResult.OuterStack
import mill.constants.OutFiles.millChromeProfile
import mill.constants.OutFiles.millProfile
import mill.internal.JsonArrayLogger
import mill.resolve.Resolve

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
  val outPath: os.Path = module.moduleDir / "out"

  if (resetSourcePath) {
    os.remove.all(module.moduleDir)
    os.makeDir.all(module.moduleDir)

    for (sourceFileRoot <- sourceRoot) {
      os.copy.over(sourceFileRoot, module.moduleDir, createFolders = true)
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
        systemStreams0 = new SystemStreams(out = outStream, err = errStream, in = inStream),
        debugEnabled = debugEnabled,
        titleText = "",
        terminfoPath = os.temp(),
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

  val execution = new mill.exec.Execution(
    baseLogger = logger,
    profileLogger = new mill.internal.JsonArrayLogger.Profile(outPath / millProfile),
    workspace = module.moduleDir,
    outPath = outPath,
    externalOutPath = outPath,
    rootModule = module,
    classLoaderSigHash = 0,
    classLoaderIdentityHash = 0,
    workerCache = collection.mutable.Map.empty,
    env = env,
    failFast = failFast,
    ec = ec,
    codeSignatures = Map(),
    systemExit = (reason, exitCode) =>
      throw Exception(s"systemExit called: reason=$reason, exitCode=$exitCode"),
    exclusiveSystemStreams = new SystemStreams(outStream, errStream, inStream),
    getEvaluator = () => evaluator,
    offline = offline,
    enableTicker = false
  )

  val evaluator: Evaluator = new mill.eval.EvaluatorImpl(
    allowPositionalCommandArgs = false,
    selectiveExecution = false,
    execution = execution,
    scriptModuleResolver = (_, _, _, _) => Nil
  )

  def apply(args: String*): Either[ExecResult.Failing[?], UnitTester.Result[Seq[?]]] = {
    Evaluator.withCurrentEvaluator(evaluator) {
      Resolve.Tasks.resolve(
        evaluator.rootModule,
        args,
        SelectMode.Separated,
        scriptModuleResolver = (_, _, _) => Nil
      )
    } match {
      case Result.Failure(err) => Left(ExecResult.Failure(err))
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

    val evaluated = evaluator.execute(tasks).executionResults

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

    val evaluated = evaluator.execute(tasks).executionResults
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
        PathRef.outPathOverride.withValue(Some(outPath)) {
          tester(this)
        }
      }
    } finally close()
  }

  def closeWithoutCheckingLeaks(): Unit = {
    for (case (_, Val(obsolete: AutoCloseable)) <- evaluator.workerCache.values) {
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
