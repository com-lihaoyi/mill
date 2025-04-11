package mill.idea

import mill.given
import mill.Task
import mill.api.Result
import mill.define.{Command, Discover, Evaluator, ExternalModule}
import mill.api.internal.EvaluatorApi

object PackageExternalModule extends mill.define.PackageExternalModule(GenIdea)
object GenIdea extends ExternalModule with mill.define.TaskModule {
  def defaultCommandName() = "idea"
  def idea(allBootstrapEvaluators: EvaluatorApi.AllBootstrapEvaluators)
      : Command[Unit] =
    Task.Command(exclusive = true) {
      GenIdeaImpl(evaluators = allBootstrapEvaluators.value).run()
    }

  override lazy val millDiscover = Discover[this.type]
}
