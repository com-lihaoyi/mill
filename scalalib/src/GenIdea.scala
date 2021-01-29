package mill.scalalib

import mill.T
import mill.define.{Discover, ExternalModule}
import mill.eval.Evaluator

object GenIdea extends ExternalModule {

  def idea(ev: Evaluator) = T.command {
    mill.scalalib.GenIdeaImpl(
      ev,
      implicitly,
      ev.rootModule,
      ev.rootModule.millDiscover
    ).run()
  }

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  override lazy val millDiscover = Discover[this.type]
}
