package mill.scalaplugin

import ammonite.ops.Path
import mill.define.{Target, Task}
import mill.discover.Mirror
import mill.eval.{Evaluator, Result}
import mill.util.{DummyLogger, OSet, PrintLogger}

object TestEvaluator {


  def resolveDestPaths(workspacePath: Path)(t: Mirror.LabelledTarget[_]): (Path, Path) = {
    new Evaluator(workspacePath, Map.empty, DummyLogger).resolveDestPaths(t)
  }

  def eval[T](mapping: Map[Target[_], Mirror.LabelledTarget[_]],
              workspacePath: Path)(t: Task[T]): Either[Result.Failing, (T, Int)] = {
    val evaluator = new Evaluator(workspacePath, mapping, DummyLogger)
    val evaluated = evaluator.evaluate(OSet(t))

    if (evaluated.failing.keyCount == 0) {
      Right(
        Tuple2(
          evaluated.rawValues.head.asInstanceOf[Result.Success[T]].value,
          evaluated.evaluated.collect {
            case t: Target[_] if mapping.contains(t) => t
            case t: mill.define.Command[_]           => t
          }.size
        ))
    } else {
      Left(
        evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next())
    }
  }

}
