package mill.idea

import mill.given
import mill.Task
import mill.api.Result
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.api.ExecResult
import scala.util.control.NonFatal

object GenIdea extends ExternalModule with mill.define.TaskModule {
  def defaultCommandName() = "idea"
  def idea(allBootstrapEvaluators: Evaluator.AllBootstrapEvaluators): Command[Unit] = Task.Command {
    try {
      Result.Success(GenIdeaImpl(
        evaluators = allBootstrapEvaluators.value
      ).run())
    } catch {
      case GenIdeaImpl.GenIdeaException(m) => Result.Failure(m)
      case NonFatal(e) =>
        ExecResult.Exception(e, new ExecResult.OuterStack(new java.lang.Exception().getStackTrace))
    }
  }

  override lazy val millDiscover = Discover[this.type]
}
