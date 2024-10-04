package mill.scalalib

import mill.{T, Task}
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.resolve.SelectMode

@deprecated("Use mill.idea.GenIdea instead", "Mill 0.11.2")
object GenIdea extends ExternalModule {

  @deprecated("Use mill.idea.GenIdea/ instead", "Mill 0.11.2")
  def idea(ev: Evaluator): Command[Unit] = Task.Command {
    T.log.error(
      "mill.scalalib.GenIdea/idea is deprecated. Please use mill.idea.GenIdea/ instead."
    )
    mill.main.RunScript.evaluateTasksNamed(
      ev,
      Seq(
        "mill.idea.GenIdea/"
      ),
      selectMode = SelectMode.Separated
    )
    ()
  }

  override lazy val millDiscover: Discover = Discover[this.type]
}
