package mill.util

import mill.define.{Input, Target, Task}
import mill.api.Result.OuterStack
import mill.eval.{Evaluator, Result}
import mill.api.Strict.Agg
import utest.assert
import utest.framework.TestPath

import language.experimental.macros
object TestEvaluator{
  val externalOutPath = os.pwd / 'target / 'external


  def static(module: TestUtil.BaseModule)(implicit fullName: sourcecode.FullName) = {
    new TestEvaluator(module)(fullName, TestPath(Nil))
  }
}

class TestEvaluator(module: TestUtil.BaseModule, failFast: Boolean = false)
                                            (implicit fullName: sourcecode.FullName,
                                             tp: TestPath){
  val outPath =  TestUtil.getOutPath()

//  val logger = DummyLogger
  val logger = new PrintLogger(
    colored = true, disableTicker=false,
    ammonite.util.Colors.Default, System.out, System.out, System.err, System.in, debugEnabled = false
 )
  val evaluator = new Evaluator(Ctx.defaultHome, outPath, TestEvaluator.externalOutPath, module, logger, failFast = failFast)

  def apply[T](t: Task[T]): Either[Result.Failing[T], (T, Int)] = {
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
        ))
    } else {
      Left(
        evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next()
          .asInstanceOf[Result.Failing[T]]
      )
    }
  }

  def fail(target: Target[_], expectedFailCount: Int, expectedRawValues: Seq[Result[_]]) = {

    val res = evaluator.evaluate(Agg(target))

    val cleaned = res.rawValues.map{
      case Result.Exception(ex, _) => Result.Exception(ex, new OuterStack(Nil))
      case x => x
    }

    assert(
      cleaned == expectedRawValues,
      res.failing.keyCount == expectedFailCount
    )

  }

  def check(targets: Agg[Task[_]], expected: Agg[Task[_]]) = {
    val evaluated = evaluator.evaluate(targets)
      .evaluated
      .flatMap(_.asTarget)
      .filter(module.millInternal.targets.contains)
      .filter(!_.isInstanceOf[Input[_]])
    assert(evaluated == expected)
  }

}
