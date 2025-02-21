package mill.idea

import mill.given
import mill.Task
import mill.api.Result
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator

object GenIdea extends ExternalModule with mill.define.TaskModule {
  def defaultCommandName() = "idea"
  def idea(allBootstrapEvaluators: Evaluator.AllBootstrapEvaluators): Command[Unit] = Task.Command {
    GenIdeaImpl(evaluators = allBootstrapEvaluators.value).run()
  }

  override lazy val millDiscover = Discover[this.type]
}
