package mill.main

import mainargs.TokensReader
import mill.eval.Evaluator
import mill.define.{Args, SelectMode, Target, Task}

case class Tasks[T](value: Seq[mill.define.NamedTask[T]])

object Tasks {
  class TokenReader[T]() extends mainargs.TokensReader.Simple[Tasks[T]]{
    def shortName = "<tasks>"
    def read(s: Seq[String]) = {
      ResolveTasks.resolve(
        Evaluator.currentEvaluator.get,
        s,
        SelectMode.Single
      ).map(x => Tasks(x.asInstanceOf[Seq[mill.define.NamedTask[T]]]))
    }
    override def alwaysRepeatable = false
    override def allowEmpty = false
  }
}

class EvaluatorTokenReader[T]() extends mainargs.TokensReader.Simple[mill.eval.Evaluator]{
      def shortName = "<eval>"
  def read(s: Seq[String]) = Right(Evaluator.currentEvaluator.get.asInstanceOf[mill.eval.Evaluator])
  override def alwaysRepeatable = false
  override def allowEmpty = true
}

/**
 * Transparently handle `Task[T]` like simple `T` but lift the result into a T.task.
 */
class SimpleTaskTokenReader[T](tokensReaderOfT: TokensReader.Simple[T])
    extends mainargs.TokensReader.Simple[Task[T]]{
  def shortName = tokensReaderOfT.shortName
  def read(s: Seq[String]) = tokensReaderOfT.read(s).map(t => Target.task(t))
  override def alwaysRepeatable = tokensReaderOfT.alwaysRepeatable
  override def allowEmpty = tokensReaderOfT.allowEmpty
}

class LeftoverTaskTokenReader[T](tokensReaderOfT: TokensReader.Leftover[T, _])
    extends mainargs.TokensReader.Leftover[Task[T], T]{
  def read(strs: Seq[String]): Either[String, Task[T]] = tokensReaderOfT.read(strs).map(t => Target.task(t))
  def wrapped = tokensReaderOfT
}

object TokenReaders {
  implicit def millEvaluatorTokenReader[T] = new mill.main.EvaluatorTokenReader[T]()

  implicit def millTasksTokenReader[T]: mainargs.TokensReader[Tasks[T]] =
    new mill.main.Tasks.TokenReader[T]()

  implicit def millArgsTokenReader: mainargs.TokensReader.ShortNamed[Args] =
    new TokensReader.Leftover[Args, String]{
      def read(strs: Seq[String]) = Right(new Args(strs))
      def wrapped = implicitly[TokensReader.ShortNamed[String]]
    }

  implicit def millTaskTokenReader[T](implicit
      tokensReaderOfT: TokensReader.ShortNamed[T]
  ): TokensReader.ShortNamed[Task[T]] = tokensReaderOfT match{
    case t: TokensReader.Simple[_] => new SimpleTaskTokenReader[T](t)
    case t: TokensReader.Leftover[_, _] => new LeftoverTaskTokenReader[T](t)
  }

}
