package mill.api

import mainargs.TokensReader

/**
 * Transparently handle `Task[T]` like simple `T` but lift the result into a [[Task.Anon]].
 */
private[mill] class SimpleTaskTokenReader[T](tokensReaderOfT: TokensReader.Simple[T])
    extends TokensReader.Simple[Task[T]] {
  def shortName = tokensReaderOfT.shortName
  def read(s: Seq[String]): Either[String, Task[T]] =
    tokensReaderOfT.read(s).map(t => mill.api.Task.Anon(t))
  override def alwaysRepeatable = tokensReaderOfT.alwaysRepeatable
  override def allowEmpty = tokensReaderOfT.allowEmpty
}
