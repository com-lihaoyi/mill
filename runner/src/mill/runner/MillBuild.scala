package mill.runner

import mill.T
import mill.define.{Command, Discover, ExternalModule, Module}
import mill.eval.Evaluator.AllBootstrapEvaluators

trait MillBuild extends Module {

  /**
   * Count of the nested build-levels, the main project and all its nested meta-builds.
   * If you run this on a meta-build, the non-meta-builds are not included.
   */
  def levelCount(evaluators: AllBootstrapEvaluators): Command[Int] = T.command {
    evaluators.value.size
  }

}

object MillBuild extends ExternalModule with MillBuild {
  override lazy val millDiscover = Discover[this.type]
}
