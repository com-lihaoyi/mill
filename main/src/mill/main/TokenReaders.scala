package mill.main

import mainargs.TokensReader
import mill.eval.Evaluator
import mill.define.{Args, Target, Task}
import mill.resolve.{Resolve, SelectMode}
import mill.resolve.SimpleTaskTokenReader

case class Tasks[T](value: Seq[mill.define.NamedTask[T]])

object Tasks {
  private[main] class TokenReader[T]() extends mainargs.TokensReader.Simple[Tasks[T]] {
    def shortName = "<tasks>"
    def read(s: Seq[String]) = {
      Resolve.Tasks.resolve(
        Evaluator.currentEvaluator.value.rootModule,
        s,
        SelectMode.Separated
      ).map(x => Tasks(x.asInstanceOf[Seq[mill.define.NamedTask[T]]]))
    }
    override def alwaysRepeatable = false
    override def allowEmpty = false
  }
}

private[mill] class EvaluatorTokenReader[T]() extends mainargs.TokensReader.Constant[Evaluator] {
  def read(): Either[String, Evaluator] = Right(Evaluator.currentEvaluator.value)
}
private[mill] class AllEvaluatorsTokenReader[T]()
    extends mainargs.TokensReader.Constant[Seq[Evaluator]] {
  def read(): Either[String, Seq[Evaluator]] = Right(Evaluator.allEvaluators.value)
}

private class LeftoverTaskTokenReader[T](tokensReaderOfT: TokensReader.Leftover[T, _])
    extends mainargs.TokensReader.Leftover[Task[T], T] {
  def read(strs: Seq[String]): Either[String, Task[T]] =
    tokensReaderOfT.read(strs).map(t => Target.task(t))
  def shortName = tokensReaderOfT.shortName
}

object TokenReaders {
  implicit def millEvaluatorTokenReader[T]: mainargs.TokensReader[Evaluator] =
    new mill.main.EvaluatorTokenReader[T]()

  implicit def millAllEvaluatorsTokenReader[T]: mainargs.TokensReader[Seq[Evaluator]] =
    new mill.main.AllEvaluatorsTokenReader[T]()

  implicit def millTasksTokenReader[T]: mainargs.TokensReader[Tasks[T]] =
    new mill.main.Tasks.TokenReader[T]()

  implicit def millArgsTokenReader: mainargs.TokensReader.ShortNamed[Args] =
    new TokensReader.Leftover[Args, String] {
      def read(strs: Seq[String]) = Right(new Args(strs))
      def shortName = implicitly[TokensReader.ShortNamed[String]].shortName
    }

  implicit def millTaskTokenReader[T](implicit
      tokensReaderOfT: TokensReader.ShortNamed[T]
  ): TokensReader[Task[T]] = tokensReaderOfT match {
    case t: TokensReader.Simple[_] => new SimpleTaskTokenReader[T](t)
    case t: TokensReader.Leftover[_, _] => new LeftoverTaskTokenReader[T](t)
  }

}
