package mill.scalalib

import mill.{Task, T}
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.resolve.SelectMode

@deprecated("Use mill.idea.GenIdea instead", "Mill 0.11.2")
object GenIdea extends ExternalModule {

  @deprecated("Use mill.idea.GenIdea/idea instead", "Mill 0.11.2")
  def idea(ev: Evaluator): Command[Unit] = Task.command {
    Task.log.error(
      "mill.scalalib.GenIdea/idea is deprecated. Please use mill.idea.GenIdea/idea instead."
    )
    mill.main.RunScript.evaluateTasksNamed(
      ev,
      Seq(
        "mill.idea.GenIdea/idea"
      ),
      selectMode = SelectMode.Separated
    )
    ()
  }

  override val millDiscover: Discover[this.type] = Discover[this.type]
}
