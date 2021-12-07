package mill.main

import mill.eval.Evaluator
import mill.define.SelectMode

case class Tasks[T](value: Seq[mill.define.NamedTask[T]])

object Tasks {

  class Scopt[T]()
      extends mainargs.TokensReader[Tasks[T]](
        shortName = "<tasks>",
        read = s =>
          RunScript.resolveTasks(
            mill.main.ResolveTasks,
            Evaluator.currentEvaluator.get,
            s,
            SelectMode.Single
          ).map(x => Tasks(x.asInstanceOf[Seq[mill.define.NamedTask[T]]])),
        alwaysRepeatable = false,
        allowEmpty = false
      )
}

class EvaluatorScopt[T]()
    extends mainargs.TokensReader[mill.eval.Evaluator](
      shortName = "<eval>",
      read = s => Right(Evaluator.currentEvaluator.get.asInstanceOf[mill.eval.Evaluator]),
      alwaysRepeatable = false,
      allowEmpty = true,
      noTokens = true
    )
