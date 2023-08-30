package mill.runner

import mill.T
import mill.define.{Command, Discover, ExternalModule, Module}
import mill.eval.Evaluator.AllBootstrapEvaluators

trait MillBuild extends Module {

  def frameCount(evaluators: AllBootstrapEvaluators): Command[Int] = T.command {
    val count = evaluators.value.size
    T.log.outputStream.println(s"${count}")
    count
  }

}

object MillBuild extends ExternalModule with MillBuild {
  override lazy val millDiscover = Discover[this.type]
}
