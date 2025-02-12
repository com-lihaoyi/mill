package mill.main

import mill.eval.Evaluator
import mill.define.SelectMode

case class Tasks[T](value: Seq[mill.define.NamedTask[T]])

object Tasks {
  def resolveMainDefault[T](tokens: String*): Tasks[T] = {
    new Tasks.TokenReader[T]()
      .read(tokens)
      .getOrElse(sys.error("Unable to resolve: " + tokens.mkString(" ")))
  }
  private[mill] class TokenReader[T]() extends mainargs.TokensReader.Simple[Tasks[T]] {
    def shortName = "tasks"
    def read(s: Seq[String]): Either[String, Tasks[T]] = {
      Evaluator.currentEvaluator.value.resolveTasks(
        s,
        SelectMode.Separated
      ).map(x => Tasks(x.asInstanceOf[Seq[mill.define.NamedTask[T]]]))
        .toEither
    }
    override def alwaysRepeatable = false
    override def allowEmpty = false
  }
}
