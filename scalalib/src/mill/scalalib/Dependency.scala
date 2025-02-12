package mill.scalalib

import mill.{Task, given}
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.scalalib.dependency.{DependencyUpdatesImpl, Format}
import mill.scalalib.dependency.updates.ModuleDependenciesUpdates

object Dependency extends ExternalModule {

  /** Calculate possible dependency updates. */
  def updates(
      ev: Evaluator,
      allowPreRelease: Boolean = false
  ): Command[Seq[ModuleDependenciesUpdates]] =
    Task.Command {
      DependencyUpdatesImpl(
        ev,
        implicitly,
        ev.rootModule,
        ev.rootModule.moduleCtx.discover,
        allowPreRelease
      )
    }

  /** Show possible dependency updates. */
  def showUpdates(
      ev: Evaluator,
      allowPreRelease: Boolean = false,
      format: Format = Format.PerModule
  ): Command[Unit] = Task.Command {
    DependencyUpdatesImpl.showAllUpdates(updates(ev, allowPreRelease)(), format)
  }

  lazy val millDiscover = Discover[this.type]
}
