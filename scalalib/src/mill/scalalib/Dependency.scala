package mill.scalalib

import mill.T
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.scalalib.dependency.{DependencyUpdatesImpl, Format}
import mill.scalalib.dependency.updates.ModuleDependenciesUpdates
import unroll.Unroll

object Dependency extends ExternalModule {

  /** Calculate possible dependency updates. */
  def updates(
      ev: Evaluator,
      allowPreRelease: Boolean = false
  ): Command[Seq[ModuleDependenciesUpdates]] =
    T.command {
      DependencyUpdatesImpl(
        ev,
        implicitly,
        ev.rootModule,
        ev.rootModule.millDiscover,
        allowPreRelease
      )
    }

  /** Show possible dependency updates. */
  def showUpdates(
      ev: Evaluator,
      allowPreRelease: Boolean = false,
      @Unroll format: Format = Format.PerModule
  ): Command[Unit] = T.command {
    DependencyUpdatesImpl.showAllUpdates(updates(ev, allowPreRelease)(), format)
  }

  lazy val millDiscover: Discover[Dependency.this.type] = Discover[this.type]
}
