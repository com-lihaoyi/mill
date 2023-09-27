package mill.scalalib

import mill.T
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.resolve.SelectMode

@deprecated("Use mill.idea.GenIdea instead", "Mill 0.11.2")
object GenIdea extends ExternalModule {

  @deprecated("Use mill.idea.GenIdea/idea instead", "Mill 0.11.2")
  def idea(ev: Evaluator): Command[Unit] = T.command {
    T.log.error(
      "mill.scalalib.GenIdea/idea is deprecated. Please use mill.idea.GenIdea/idea instead."
    )
    mill.main.RunScript.evaluateTasksNamed(
      ev,
      Seq(
        "mill.idea.GenIdea/idea"
      ),
      selectMode = SelectMode.Separated,
      onlyDeps = false
    )
    ()
  }

  override lazy val millDiscover = Discover[this.type]
}
