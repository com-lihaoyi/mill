package mill.testkit

import mill.define._
import mill.api.Result
import mill.api.Result.OuterStack
import mill.api.Strict.Agg
import java.io.{InputStream, PrintStream}
import mill.define.{Input, Target, Task}
import mill.eval.Evaluator
import language.experimental.macros
import mill.api.{DummyInputStream, Result}

import scala.collection.mutable

trait MillTestKit {

  def defaultTargetDir: os.Path =
    sys.env.get("MILL_TESTKIT_BASEDIR").map(os.pwd / os.RelPath(_)).getOrElse(os.temp.dir())

  def targetDir: os.Path = defaultTargetDir

  def externalOutPath: os.Path = targetDir / "external"

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
      disableTicker = false,
      mill.util.Colors.Default.info,
      mill.util.Colors.Default.error,
      outStream,
      outStream,
      outStream,
      inStream,
      debugEnabled = debugEnabled,
      context = ""
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
    val evaluator = Evaluator(
      mill.api.Ctx.defaultHome,
      outPath,
      externalOutPath,
      module,
      logger
    ).withFailFast(failFast).withThreadCount(threads).withEnv(env)

    def apply[T](t: Task[T]): Either[mill.api.Result.Failing[T], (T, Int)] = {
      val evaluated = evaluator.evaluate(Agg(t))

      if (evaluated.failing.keyCount == 0) {
        Right(
          Tuple2(
            evaluated.rawValues.head.asInstanceOf[Result.Success[T]].value,
            evaluated.evaluated.collect {
              case t: Target[_]
                  if module.millInternal.targets.contains(t)
                    && !t.isInstanceOf[Input[_]]
                    && !t.ctx.external => t
              case t: mill.define.Command[_] => t
            }.size
          )
        )
      } else {
        Left(
          evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next()
            .asInstanceOf[Result.Failing[T]]
        )
      }
    }

    def fail(target: Target[_], expectedFailCount: Int, expectedRawValues: Seq[Result[_]]): Unit = {

      val res = evaluator.evaluate(Agg(target))

      val cleaned = res.rawValues.map {
        case Result.Exception(ex, _) => Result.Exception(ex, new OuterStack(Nil))
        case x => x
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
        .filter(!_.isInstanceOf[Input[_]])
      assert(
        evaluated == expected,
        s"evaluated is not equal expected. evaluated=${evaluated}, expected=${expected}"
      )
    }

  }

}
