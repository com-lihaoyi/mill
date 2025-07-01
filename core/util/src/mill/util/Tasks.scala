package mill.util

import mill.api.{Evaluator, SelectMode}

case class Tasks[T](value: Seq[mill.api.Task.Named[T]])

object Tasks {
  def resolveMainDefault[T](tokens: String*): Tasks[T] = {
    new Tasks.TokenReader[T]()
      .read(tokens)
      .getOrElse(sys.error("Unable to resolve: " + tokens.mkString(" ")))
  }
  private[mill] class TokenReader[T]() extends mainargs.TokensReader.Simple[Tasks[T]] {
    def shortName = "tasks"
    def read(s: Seq[String]): Either[String, Tasks[T]] = {
      Evaluator.currentEvaluator.resolveTasks(
        s,
        SelectMode.Separated
      ).map(x => Tasks(x.asInstanceOf[Seq[mill.api.Task.Named[T]]]))
        .toEither
    }
    override def alwaysRepeatable = false
    override def allowEmpty = false
  }
}
