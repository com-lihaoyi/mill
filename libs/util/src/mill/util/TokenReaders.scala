package mill.util

import mainargs.TokensReader
import mill.api.*
import mill.api.daemon.internal.EvaluatorApi
import mill.util.{LeftoverTaskTokenReader, TokenReaders}
private[mill] class EvaluatorTokenReader[T]() extends mainargs.TokensReader.Constant[Evaluator] {
  def read(): Either[String, Evaluator] = Right(
    new EvaluatorProxy(() => Evaluator.currentEvaluator)
  )
}

private class LeftoverTaskTokenReader[T](tokensReaderOfT: TokensReader.Leftover[T, ?])
    extends mainargs.TokensReader.Leftover[Task[T], T] {
  def read(strs: Seq[String]): Either[String, Task[T]] =
    tokensReaderOfT.read(strs).map(t => mill.api.Task.Anon(t))
  def shortName = tokensReaderOfT.shortName
}

/**
 * Contains the additional instances of [[mainargs.TokensReader]] necessary to support
 * `Task.Command` entrypoints
 */
object TokenReaders extends TokenReaders
trait TokenReaders {
  implicit def millEvaluatorTokenReader[T]: mainargs.TokensReader[Evaluator] =
    new mill.util.EvaluatorTokenReader[T]()

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
}
