package mill.scalalib

import mill.scalalib.GenIdeaModule.{IdeaConfigFile, JavaFacet}

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
  lazy val millDiscover = Discover[this.type]
}
