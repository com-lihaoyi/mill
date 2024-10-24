package mill.resolve

import mainargs.TokensReader
import mill.define.{Target, Task}

/**
 * Transparently handle `Task[T]` like simple `T` but lift the result into a Task.Anon.
 */
class SimpleTaskTokenReader[T](tokensReaderOfT: TokensReader.Simple[T])
    extends mainargs.TokensReader.Simple[Task[T]] {
  def shortName = tokensReaderOfT.shortName
  def read(s: Seq[String]): Either[String, Task[T]] =
    tokensReaderOfT.read(s).map(t => Target.task(t))
  override def alwaysRepeatable = tokensReaderOfT.alwaysRepeatable
  override def allowEmpty = tokensReaderOfT.allowEmpty
}
