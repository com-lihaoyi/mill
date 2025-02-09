package mill.testkit

import mill.{Target, Task}
import mill.api.Result.OuterStack
import mill.api.{DummyInputStream, Result, SystemStreams, Val}
import mill.define.{InputImpl, TargetImpl}
import mill.eval.Evaluator
import mill.resolve.{Resolve, SelectMode}
import mill.internal.PrintLogger
import mill.api.Strict.Agg

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
  val outPath: os.Path = module.millSourcePath / "out"

  if (resetSourcePath) {
    os.remove.all(module.millSourcePath)
    os.makeDir.all(module.millSourcePath)

    for (sourceFileRoot <- Option(sourceRoot)) {
      os.copy.over(sourceFileRoot, module.millSourcePath, createFolders = true)
    }
  }

  object logger extends mill.internal.PrintLogger(
        colored = true,
        enableTicker = false,
        mill.internal.Colors.Default.info,
        mill.internal.Colors.Default.error,
        new SystemStreams(out = outStream, err = errStream, in = inStream),
        debugEnabled = debugEnabled,
        context = "",
        new PrintLogger.State()
      ) {
    val prefix: String = {
      val idx = fullName.value.lastIndexOf(".")
      if (idx > 0) fullName.value.substring(0, idx)
      else fullName.value
    }
    override def error(s: String): Unit = super.error(s"${prefix}: ${s}")
    override def info(s: String): Unit = super.info(s"${prefix}: ${s}")
    override def debug(s: String): Unit = super.debug(s"${prefix}: ${s}")
    override def ticker(s: String): Unit = super.ticker(s"${prefix}: ${s}")
  }
  val evaluator: Evaluator = mill.eval.Evaluator.make(
    mill.api.Ctx.defaultHome,
    module.millSourcePath,
    outPath,
    outPath,
    module,
    logger,
    0,
    0,
    failFast = failFast,
    threadCount = threads,
    env = env,
    methodCodeHashSignatures = Map(),
    disableCallgraph = false,
    allowPositionalCommandArgs = false,
    systemExit = _ => ???,
    exclusiveSystemStreams = new SystemStreams(outStream, errStream, inStream),
    selectiveExecution = false
  )

  def apply(args: String*): Either[Result.Failing[?], UnitTester.Result[Seq[?]]] = {
    mill.eval.Evaluator.currentEvaluator.withValue(evaluator) {
      Resolve.Tasks.resolve(evaluator.rootModule, args, SelectMode.Separated)
    } match {
      case Left(err) => Left(Result.Failure(err))
      case Right(resolved) => apply(resolved)
    }
  }

  def apply[T](task: Task[T]): Either[Result.Failing[T], UnitTester.Result[T]] = {
    apply(Seq(task)) match {
      case Left(f) => Left(f.asInstanceOf[Result.Failing[T]])
      case Right(UnitTester.Result(Seq(v), i)) =>
        Right(UnitTester.Result(v.asInstanceOf[T], i))
      case _ => ???
    }
  }

  def apply(
      tasks: Seq[Task[?]],
      dummy: DummyImplicit = null
  ): Either[Result.Failing[?], UnitTester.Result[Seq[?]]] = {
    val evaluated = evaluator.evaluate(tasks)

    if (evaluated.failing.keyCount == 0) {
      Right(
        UnitTester.Result(
          evaluated.rawValues.map(_.asInstanceOf[Result.Success[Val]].value.value),
          evaluated.evaluated.collect {
            case t: TargetImpl[_]
                if module.millInternal.targets.contains(t)
                  && !t.ctx.external => t
            case t: mill.define.Command[_] => t
          }.size
        )
      )
    } else {
      Left(
        evaluated
          .failing
          .lookupKey(evaluated.failing.keys().next)
          .items
          .next()
          .asFailing
          .get
          .map(_.value)
      )
    }
  }

  def fail(target: Target[?], expectedFailCount: Int, expectedRawValues: Seq[Result[?]]): Unit = {

    val res = evaluator.evaluate(Agg(target))

    val cleaned = res.rawValues.map {
      case Result.Exception(ex, _) => Result.Exception(ex, new OuterStack(Nil))
      case x => x.map(_.value)
    }

    assert(
      cleaned == expectedRawValues,
      res.failing.keyCount == expectedFailCount
    )

  }

  def check(targets: Agg[Task[?]], expected: Agg[Task[?]]): Unit = {

    val evaluated = evaluator.evaluate(targets)
      .evaluated
      .flatMap(_.asTarget)
      .filter(module.millInternal.targets.contains)
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
