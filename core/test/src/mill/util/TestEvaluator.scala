package mill.util

import ammonite.ops.{Path, pwd}
import mill.define.Discover.applyImpl
import mill.define.{Discover, Input, Target, Task}
import mill.eval.{Evaluator, Result}
import mill.util.Strict.Agg
import utest.assert

import language.experimental.macros
object TestEvaluator{
  implicit def implicitDisover[T]: Discover[T] = macro applyImpl[T]
  val externalOutPath = pwd / 'target / 'external
}
class TestEvaluator[T <: TestUtil.TestBuild](module: T,
                                             workspacePath: Path,
                                             millSourcePath: Path)
                                            (implicit discover: Discover[T]){
  val logger = DummyLogger
//  val logger = new PrintLogger(true, ammonite.util.Colors.Default, System.out, System.out, System.err)
  val evaluator = new Evaluator(workspacePath, millSourcePath, TestEvaluator.externalOutPath, module, discover, logger)

  def apply[T](t: Task[T]): Either[Result.Failing, (T, Int)] = {
    val evaluated = evaluator.evaluate(Agg(t))

    if (evaluated.failing.keyCount == 0) {
      Right(
        Tuple2(
          evaluated.rawValues.head.asInstanceOf[Result.Success[T]].value,
          evaluated.evaluated.collect {
            case t: Target[_]
              if module.millInternal.targets.contains(t)
              && !t.isInstanceOf[Input[_]] => t
            case t: mill.define.Command[_] => t
          }.size
        ))
    } else {
      Left(
        evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next())
    }
  }

  def fail(target: Target[_], expectedFailCount: Int, expectedRawValues: Seq[Result[_]]) = {

    val res = evaluator.evaluate(Agg(target))

    val cleaned = res.rawValues.map{
      case Result.Exception(ex, _) => Result.Exception(ex, Nil)
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
