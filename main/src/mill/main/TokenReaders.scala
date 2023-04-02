package mill.main

import mainargs.TokensReader
import mill.eval.Evaluator
import mill.define.{SelectMode, Target, Task}

case class Tasks[T](value: Seq[mill.define.Target[T]])

object Tasks {
  class TokenReader[T]()
      extends mainargs.TokensReader[Tasks[T]](
        shortName = "<tasks>",
        read = s =>
          RunScript.resolveTasks(
            mill.main.ResolveTasks,
            Evaluator.currentEvaluator.get,
            s,
            SelectMode.Single
          ).map(x => Tasks(x.asInstanceOf[Seq[mill.define.Target[T]]])),
        alwaysRepeatable = false,
        allowEmpty = false
      )
}

class EvaluatorTokenReader[T]()
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
class TaskTokenReader[T](tokensReaderOfT: TokensReader[T])
    extends mainargs.TokensReader[Task[T]](
      shortName = tokensReaderOfT.shortName,
      read = s => tokensReaderOfT.read(s).map(t => Target.task(t)),
      alwaysRepeatable = tokensReaderOfT.alwaysRepeatable,
      allowEmpty = tokensReaderOfT.allowEmpty,
      noTokens = tokensReaderOfT.noTokens
    )

object TokenReaders{
  implicit def millEvaluatorTokenReader[T] = new mill.main.EvaluatorTokenReader[T]()

  implicit def millTasksTokenReader[T]: mainargs.TokensReader[Tasks[T]] =
    new mill.main.Tasks.TokenReader[T]()

  implicit def millTaskTokenReader[T](implicit tokensReaderOfT: TokensReader[T]): TokensReader[Task[T]] =
    new TaskTokenReader[T](tokensReaderOfT)
}
