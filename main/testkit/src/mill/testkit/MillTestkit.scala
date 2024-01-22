package mill.testkit

import mill._
import mill.define.{Caller, Discover, InputImpl, TargetImpl}
import mill.api.{DummyInputStream, Result, SystemStreams, Val}
import mill.api.Result.OuterStack
import mill.api.Strict.Agg

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.resolve.{Resolve, SelectMode}
import mill.util.PrintLogger
import mill.eval.EvaluatorImpl
import os.Path

trait MillTestKit {

  def defaultTargetDir: os.Path =
    sys.env.get("MILL_TESTKIT_BASEDIR").map(os.pwd / os.RelPath(_)).getOrElse(os.temp.dir())

  def targetDir: os.Path = defaultTargetDir

  def staticTestEvaluator(module: => mill.define.BaseModule)(implicit
      fullName: sourcecode.FullName
  ): TestEvaluator = {
    new TestEvaluator(module, Seq.empty)(fullName)
  }

  def getOutPath(testPath: Seq[String])(implicit fullName: sourcecode.FullName): os.Path = {
    getOutPathStatic() / testPath
  }

  def getOutPathStatic()(implicit fullName: sourcecode.FullName): os.Path = {
    targetDir / "workspace" / fullName.value.split('.')
  }

  def getSrcPathStatic()(implicit fullName: sourcecode.FullName): os.Path = {
    getSrcPathBase() / fullName.value.split('.')
  }
  def getSrcPathBase(): os.Path = {
    targetDir / "worksources"
  }

  class BaseModule(implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line
  ) extends mill.define.BaseModule(getSrcPathBase() / millModuleEnclosing0.value.split("\\.| |#"))(
        implicitly,
        implicitly,
        implicitly,
        Caller(null)
      ) {
    lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  /**
   * @param module The module under test
   * @param externalOutPath The directory under which the evaluator stores its output
   * @param failFast failFast mode enabled
   * @param threads explicitly used nr. of parallel threads
   */
  class TestEvaluator(
      module: mill.define.BaseModule,
      testPath: Seq[String],
      failFast: Boolean = false,
      threads: Option[Int] = Some(1),
      outStream: PrintStream = System.out,
      errStream: PrintStream = System.err,
      inStream: InputStream = DummyInputStream,
      debugEnabled: Boolean = false,
      extraPathEnd: Seq[String] = Seq.empty,
      env: Map[String, String] = Evaluator.defaultEnv
  )(implicit fullName: sourcecode.FullName) {
    val outPath: Path = getOutPath(testPath) / extraPathEnd

//  val logger = DummyLogger
    val logger: logger = new logger
    class logger extends mill.util.PrintLogger(
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
      module,
      logger,
      0,
      0,
      failFast = failFast,
      threadCount = threads,
      env = env,
      methodCodeHashSignatures = Map(),
      disableCallgraphInvalidation = false
    )

    def evalTokens(args: String*): Either[Result.Failing[_], (Seq[_], Int)] = {
      mill.eval.Evaluator.currentEvaluator.withValue(evaluator) {
        Resolve.Tasks.resolve(evaluator.rootModule, args, SelectMode.Separated)
      } match {
        case Left(err) => Left(Result.Failure(err))
        case Right(resolved) => apply(resolved)
      }
    }

    def apply[T](task: Task[T]): Either[Result.Failing[T], (T, Int)] = {
      apply(Seq(task)) match {
        case Left(f) => Left(f.asInstanceOf[Result.Failing[T]])
        case Right((Seq(v), i)) => Right((v.asInstanceOf[T], i))
      }
    }

    def apply(tasks: Seq[Task[_]]): Either[Result.Failing[_], (Seq[_], Int)] = {
      val evaluated = evaluator.evaluate(tasks)

      if (evaluated.failing.keyCount == 0) {
        Right(
          Tuple2(
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

}
