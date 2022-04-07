package mill.main

import mainargs.TokensReader
import mill.eval.Evaluator
import mill.define.{SelectMode, Target, Task}

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

/**
 * Transparently handle `Task[T]` like simple `T` but lift the result into a T.task.
 */
class TaskScopt[T](tokensReaderOfT: TokensReader[T])
    extends mainargs.TokensReader[Task[T]](
      shortName = tokensReaderOfT.shortName,
      read = s => tokensReaderOfT.read(s).map(t => Target.task(t)),
      alwaysRepeatable = tokensReaderOfT.alwaysRepeatable,
      allowEmpty = tokensReaderOfT.allowEmpty,
      noTokens = tokensReaderOfT.noTokens
    )
