package mill.idea

import mill.T
import mill.api.Result
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator

import scala.util.control.NonFatal

object GenIdea extends ExternalModule {

  def idea(allBootstrapEvaluators: Evaluator.AllBootstrapEvaluators): Command[Unit] = T.command {
    try {
      Result.Success(GenIdeaImpl(
        evaluators = Evaluator.allBootstrapEvaluators.value.value
      ).run())
    } catch {
      case GenIdeaImpl.GenIdeaException(m) => Result.Failure(m)
      case NonFatal(e) =>
        Result.Exception(e, new Result.OuterStack(new java.lang.Exception().getStackTrace))
    }
  }

  override lazy val millDiscover = Discover[this.type]
}
