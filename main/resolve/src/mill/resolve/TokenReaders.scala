package mill.resolve

import mainargs.TokensReader
import mill.define.{Target, Task, Args}


/**
 * Transparently handle `Task[T]` like simple `T` but lift the result into a T.task.
 */
class SimpleTaskTokenReader[T](tokensReaderOfT: TokensReader.Simple[T])
    extends mainargs.TokensReader.Simple[Task[T]] {
  def shortName = tokensReaderOfT.shortName
  def read(s: Seq[String]) = tokensReaderOfT.read(s).map(t => Target.task(t))
  override def alwaysRepeatable = tokensReaderOfT.alwaysRepeatable
  override def allowEmpty = tokensReaderOfT.allowEmpty
}
