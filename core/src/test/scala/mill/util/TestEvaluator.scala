package mill.util

import ammonite.ops.Path
import mill.define.{Input, Target, Task}
import mill.discover.{Discovered, Mirror}
import mill.eval.{Evaluator, Result}

class TestEvaluator(mapping: Discovered.Mapping[_],
                    workspacePath: Path,
                    basePath: Path){
  val evaluator = new Evaluator(workspacePath, basePath, mapping, DummyLogger)

  def apply[T](t: Task[T]): Either[Result.Failing, (T, Int)] = {
    val evaluated = evaluator.evaluate(OSet(t))

    if (evaluated.failing.keyCount == 0) {
      Right(
        Tuple2(
          evaluated.rawValues.head.asInstanceOf[Result.Success[T]].value,
          evaluated.evaluated.collect {
            case t: Target[_] if mapping.targets.contains(t) && !t.isInstanceOf[Input[_]] => t
            case t: mill.define.Command[_]           => t
          }.size
        ))
    } else {
      Left(
        evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next())
    }
  }

}
