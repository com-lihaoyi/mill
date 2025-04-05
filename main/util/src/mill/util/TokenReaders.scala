package mill.util

import mainargs.TokensReader
import mill.define.{Args, Evaluator, EvaluatorProxy, Task, SimpleTaskTokenReader}

private[mill] class EvaluatorTokenReader[T]() extends mainargs.TokensReader.Constant[Evaluator] {
  def read(): Either[String, Evaluator] = Right(
    new EvaluatorProxy(Evaluator.currentEvaluator)
  )
}
private[mill] class AllEvaluatorsTokenReader[T]()
    extends mainargs.TokensReader.Constant[mill.runner.api.EvaluatorApi.AllBootstrapEvaluators] {
  def read(): Either[String, mill.runner.api.EvaluatorApi.AllBootstrapEvaluators] =
    Right(mill.runner.api.EvaluatorApi.allBootstrapEvaluators.value)
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
    new mill.util.EvaluatorTokenReader[T]()

  implicit def millAllEvaluatorsTokenReader[T]
      : mainargs.TokensReader[mill.runner.api.EvaluatorApi.AllBootstrapEvaluators] =
    new mill.util.AllEvaluatorsTokenReader[T]()

  implicit def millTasksTokenReader[T]: mainargs.TokensReader[Tasks[T]] =
    new mill.util.Tasks.TokenReader[T]()

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
