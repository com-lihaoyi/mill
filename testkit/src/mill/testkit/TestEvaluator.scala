package mill.testkit

import mill.{Target, Task}
import mill.api.Result.OuterStack
import mill.api.{DummyInputStream, Result, SystemStreams, Val}
import mill.define.{InputImpl, TargetImpl}
import mill.eval.{Evaluator, EvaluatorImpl}
import mill.resolve.{Resolve, SelectMode}
import mill.testkit.MillTestKit.getOutPath
import mill.util.PrintLogger
import mill.api.Strict.Agg
import utest.framework.TestPath

import java.io.{InputStream, PrintStream}

object TestEvaluator {
  def static(module: => mill.testkit.BaseModule)(implicit
      fullName: sourcecode.FullName
  ): TestEvaluator = {
    new TestEvaluator(module)(fullName, TestPath(Nil))
  }

  case class Result[T](value: T, evalCount: Int)
}

/**
 * @param module The module under test
 * @param failFast failFast mode enabled
 * @param threads explicitly used nr. of parallel threads
 */
class TestEvaluator(
    module: mill.testkit.BaseModule,
    failFast: Boolean = false,
    threads: Option[Int] = Some(1),
    outStream: PrintStream = System.out,
    errStream: PrintStream = System.err,
    inStream: InputStream = DummyInputStream,
    debugEnabled: Boolean = false,
    extraPathEnd: Seq[String] = Seq.empty,
    env: Map[String, String] = Evaluator.defaultEnv
)(implicit fullName: sourcecode.FullName, tp: TestPath) {
  val outPath: os.Path = getOutPath(tp.value) / extraPathEnd

  object logger extends mill.util.PrintLogger(
        colored = true,
        enableTicker = true,
        mill.util.Colors.Default.info,
        mill.util.Colors.Default.error,
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
  val evaluator: EvaluatorImpl = mill.eval.EvaluatorImpl(
    mill.api.Ctx.defaultHome,
    module.millSourcePath,
    outPath,
    outPath,
    Seq(module),
    logger,
    0,
    0,
    failFast = failFast,
    threadCount = threads,
    env = env,
    methodCodeHashSignatures = Map(),
    disableCallgraphInvalidation = false
  )

  def evalTokens(args: String*): Either[Result.Failing[_], TestEvaluator.Result[Seq[_]]] = {
    mill.eval.Evaluator.currentEvaluator.withValue(evaluator) {
      Resolve.Tasks.resolve(evaluator.rootModules, args, SelectMode.Separated)
    } match {
      case Left(err) => Left(Result.Failure(err))
      case Right(resolved) => apply(resolved)
    }
  }

  def apply[T](task: Task[T]): Either[Result.Failing[T], TestEvaluator.Result[T]] = {
    apply(Seq(task)) match {
      case Left(f) => Left(f.asInstanceOf[Result.Failing[T]])
      case Right(TestEvaluator.Result(Seq(v), i)) =>
        Right(TestEvaluator.Result(v.asInstanceOf[T], i))
    }
  }

  def apply(tasks: Seq[Task[_]]): Either[Result.Failing[_], TestEvaluator.Result[Seq[_]]] = {
    val evaluated = evaluator.evaluate(tasks)

    if (evaluated.failing.keyCount == 0) {
      Right(
        TestEvaluator.Result(
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

  def fail(target: Target[_], expectedFailCount: Int, expectedRawValues: Seq[Result[_]]): Unit = {

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

  def check(targets: Agg[Task[_]], expected: Agg[Task[_]]): Unit = {

    val evaluated = evaluator.evaluate(targets)
      .evaluated
      .flatMap(_.asTarget)
      .filter(module.millInternal.targets.contains)
      .filter(!_.isInstanceOf[InputImpl[_]])
    assert(
      evaluated.toSet == expected.toSet,
      s"evaluated is not equal expected. evaluated=${evaluated}, expected=${expected}"
    )
  }

}
