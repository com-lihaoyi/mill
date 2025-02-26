package mill.main

import mainargs.TokensReader
import mill.define.{Args, Evaluator, Task}
import mill.resolve.SimpleTaskTokenReader
import mill.eval.EvaluatorProxy

private[mill] class EvaluatorTokenReader[T]() extends mainargs.TokensReader.Constant[Evaluator] {
  def read(): Either[String, Evaluator] = Right(
    new EvaluatorProxy(Evaluator.currentEvaluator)
  )
}
private[mill] class AllEvaluatorsTokenReader[T]()
    extends mainargs.TokensReader.Constant[Evaluator.AllBootstrapEvaluators] {
  def read(): Either[String, Evaluator.AllBootstrapEvaluators] =
    Right(Evaluator.allBootstrapEvaluators.value)
}

private class LeftoverTaskTokenReader[T](tokensReaderOfT: TokensReader.Leftover[T, ?])
    extends mainargs.TokensReader.Leftover[Task[T], T] {
  def read(strs: Seq[String]): Either[String, Task[T]] =
    tokensReaderOfT.read(strs).map(t => mill.define.Task.Anon(t))
  def shortName = tokensReaderOfT.shortName
}

object TokenReaders extends TokenReaders0
trait TokenReaders0 {
  implicit def millEvaluatorTokenReader[T]: mainargs.TokensReader[Evaluator] =
    new mill.main.EvaluatorTokenReader[T]()

  implicit def millAllEvaluatorsTokenReader[T]
      : mainargs.TokensReader[Evaluator.AllBootstrapEvaluators] =
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

  def `given` = () // dummy for scala 2/3 compat
}
