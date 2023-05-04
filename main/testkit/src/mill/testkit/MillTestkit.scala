package mill.testkit

import mill._
import mill.define.{Discover, InputImpl, TargetImpl}
import mill.api.{DummyInputStream, Result, SystemStreams, Val}
import mill.api.Result.OuterStack
import mill.api.Strict.Agg

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.util.PrintLogger

import language.experimental.macros

trait MillTestKit {

  def defaultTargetDir: os.Path =
    sys.env.get("MILL_TESTKIT_BASEDIR").map(os.pwd / os.RelPath(_)).getOrElse(os.temp.dir())

  def targetDir: os.Path = defaultTargetDir

  def staticTestEvaluator(module: => mill.define.BaseModule)(implicit
      fullName: sourcecode.FullName
  ) = {
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
      millModuleLine0: sourcecode.Line,
      millName0: sourcecode.Name
  ) extends mill.define.BaseModule(getSrcPathBase() / millModuleEnclosing0.value.split("\\.| |#"))(
        implicitly,
        implicitly,
        implicitly,
        implicitly,
        implicitly
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
      inStream: InputStream = DummyInputStream,
      debugEnabled: Boolean = false,
      extraPathEnd: Seq[String] = Seq.empty,
      env: Map[String, String] = Evaluator.defaultEnv
  )(implicit fullName: sourcecode.FullName) {
    val outPath = getOutPath(testPath) / extraPathEnd

//  val logger = DummyLogger
    val logger = new mill.util.PrintLogger(
      colored = true,
      enableTicker = true,
      mill.util.Colors.Default.info,
      mill.util.Colors.Default.error,
      new SystemStreams(outStream, outStream, inStream),
      debugEnabled = debugEnabled,
      context = "",
      new PrintLogger.State()
    ) {
      val prefix = {
        val idx = fullName.value.lastIndexOf(".")
        if (idx > 0) fullName.value.substring(0, idx)
        else fullName.value
      }
      override def error(s: String): Unit = super.error(s"${prefix}: ${s}")
      override def info(s: String): Unit = super.info(s"${prefix}: ${s}")
      override def debug(s: String): Unit = super.debug(s"${prefix}: ${s}")
      override def ticker(s: String): Unit = super.ticker(s"${prefix}: ${s}")
    }
    val evaluator = mill.eval.EvaluatorImpl(
      mill.api.Ctx.defaultHome,
      outPath,
      outPath,
      module,
      logger,
      0,
      failFast = failFast,
      threadCount = threads,
      env = env
    )

    def apply[T](t: Task[T]): Either[mill.api.Result.Failing[T], (T, Int)] = {
      val evaluated = evaluator.evaluate(Agg(t))

      if (evaluated.failing.keyCount == 0) {
        Right(
          Tuple2(
            evaluated.rawValues.head.asInstanceOf[Result.Success[Val]].value.value.asInstanceOf[T],
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
            .map { (x: Val) =>
              x.value.asInstanceOf[T]
            }
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
