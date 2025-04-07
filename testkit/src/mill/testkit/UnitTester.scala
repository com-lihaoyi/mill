package mill.testkit

import mill.{Target, Task}
import mill.api.ExecResult.OuterStack
import mill.api.{DummyInputStream, ExecResult, Result, SystemStreams, Val}
import mill.define.{Evaluator, InputImpl, SelectMode, TargetImpl}
import mill.resolve.Resolve
import mill.exec.JsonArrayLogger
import mill.constants.OutFiles.{millChromeProfile, millProfile}

import java.io.{InputStream, PrintStream}

object UnitTester {
  case class Result[T](value: T, evalCount: Int)
  def apply(
      module: mill.testkit.TestBaseModule,
      sourceRoot: os.Path,
      failFast: Boolean = false,
      threads: Option[Int] = Some(1),
      outStream: PrintStream = Console.out,
      errStream: PrintStream = Console.err,
      inStream: InputStream = DummyInputStream,
      debugEnabled: Boolean = false,
      env: Map[String, String] = Evaluator.defaultEnv,
      resetSourcePath: Boolean = true
  ) = new UnitTester(
    module = module,
    sourceRoot = sourceRoot,
    failFast = failFast,
    threads = threads,
    outStream = outStream,
    errStream = errStream,
    inStream = inStream,
    debugEnabled = debugEnabled,
    env = env,
    resetSourcePath = resetSourcePath
  )
}

/**
 * @param module The module under test
 * @param failFast failFast mode enabled
 * @param threads explicitly used nr. of parallel threads
 */
class UnitTester(
    module: mill.testkit.TestBaseModule,
    sourceRoot: os.Path,
    failFast: Boolean,
    threads: Option[Int],
    outStream: PrintStream,
    errStream: PrintStream,
    inStream: InputStream,
    debugEnabled: Boolean,
    env: Map[String, String],
    resetSourcePath: Boolean
)(implicit fullName: sourcecode.FullName) extends AutoCloseable {
  val outPath: os.Path = module.moduleDir / "out"

  if (resetSourcePath) {
    os.remove.all(module.moduleDir)
    os.makeDir.all(module.moduleDir)

    for (sourceFileRoot <- Option(sourceRoot)) {
      os.copy.over(sourceFileRoot, module.moduleDir, createFolders = true)
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
        currentTimeMillis = () => System.currentTimeMillis()
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

  val execution = new mill.exec.Execution(
    baseLogger = logger,
    chromeProfileLogger = new JsonArrayLogger.ChromeProfile(outPath / millChromeProfile),
    profileLogger = new JsonArrayLogger.Profile(outPath / millProfile),
    workspace = module.moduleDir,
    outPath = outPath,
    externalOutPath = outPath,
    rootModule = module,
    classLoaderSigHash = 0,
    classLoaderIdentityHash = 0,
    workerCache = collection.mutable.Map.empty,
    env = env,
    failFast = failFast,
    threadCount = threads,
    codeSignatures = Map(),
    systemExit = _ => ???,
    exclusiveSystemStreams = new SystemStreams(outStream, errStream, inStream),
    getEvaluator = () => evaluator
  )

  val evaluator: Evaluator = new mill.eval.EvaluatorImpl(
    allowPositionalCommandArgs = false,
    selectiveExecution = false,
    execution = execution
  )

  def apply(args: String*): Either[ExecResult.Failing[?], UnitTester.Result[Seq[?]]] = {
    Evaluator.currentEvaluator0.withValue(evaluator) {
      Resolve.Tasks.resolve(evaluator.rootModule, args, SelectMode.Separated)
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

  def apply(
      tasks: Seq[Task[?]],
      dummy: DummyImplicit = null
  ): Either[ExecResult.Failing[?], UnitTester.Result[Seq[?]]] = {
    val evaluated = evaluator.execute(tasks).executionResults

    if (evaluated.transitiveFailing.isEmpty) {
      Right(
        UnitTester.Result(
          evaluated.results.map(_.asInstanceOf[ExecResult.Success[Val]].value.value),
          evaluated.uncached.collect {
            case t: TargetImpl[_]
                if module.moduleInternal.targets.contains(t)
                  && !t.ctx.external => t
            case t: mill.define.Command[_] => t
          }.size
        )
      )
    } else Left(evaluated.transitiveFailing.values.head)
  }

  def fail(
      target: Target[?],
      expectedFailCount: Int,
      expectedRawValues: Seq[ExecResult[?]]
  ): Unit = {

    val res = evaluator.execute(Seq(target)).executionResults

    val cleaned = res.results.map {
      case ExecResult.Exception(ex, _) => ExecResult.Exception(ex, new OuterStack(Nil))
      case x => x.map(_.value)
    }

    assert(cleaned == expectedRawValues)
    assert(res.transitiveFailing.size == expectedFailCount)

  }

  def check(targets: Seq[Task[?]], expected: Seq[Task[?]]): Unit = {

    val evaluated = evaluator.execute(targets).executionResults
      .uncached
      .flatMap(_.asTarget)
      .filter(module.moduleInternal.targets.contains)
      .filter(!_.isInstanceOf[InputImpl[?]])
    assert(
      evaluated.toSet == expected.toSet,
      s"evaluated is not equal expected. evaluated=${evaluated}, expected=${expected}"
    )
  }

  def scoped[T](tester: UnitTester => T): T = {
    try tester(this)
    finally close()
  }

  def close(): Unit = {
    for (case (_, Val(obsolete: AutoCloseable)) <- evaluator.workerCache.values) {
      obsolete.close()
    }
    evaluator.close()
  }
}
