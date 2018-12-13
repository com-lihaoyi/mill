package mill.scalalib

import mill.T
import mill.define.{Discover, ExternalModule}
import mill.eval.Evaluator
import mill.main.EvaluatorScopt
import mill.scalalib.dependency.DependencyUpdatesImpl

object Dependency extends ExternalModule {

  def updates(ev: Evaluator, allowPreRelease: Boolean = false) =
    T.command {
      DependencyUpdatesImpl(implicitly,
                            ev.rootModule,
                            ev.rootModule.millDiscover,
                            allowPreRelease)
    }

  implicit def millScoptEvaluatorReads[T]: EvaluatorScopt[T] =
    new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover: Discover[Dependency.this.type] = Discover[this.type]
}
